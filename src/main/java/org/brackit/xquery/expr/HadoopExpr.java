/*
 * [New BSD License]
 * Copyright (c) 2011-2013, Brackit Project Team <info@brackit.org>  
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Brackit Project Team nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.brackit.xquery.expr;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.brackit.hadoop.job.XQueryJob;
import org.brackit.hadoop.job.XQueryJobConf;
import org.brackit.hadoop.runtime.HadoopQueryContext;
import org.brackit.xquery.ErrorCode;
import org.brackit.xquery.QueryContext;
import org.brackit.xquery.QueryException;
import org.brackit.xquery.XQuery;
import org.brackit.xquery.compiler.AST;
import org.brackit.xquery.compiler.XQ;
import org.brackit.xquery.compiler.XQExt;
import org.brackit.xquery.module.StaticContext;
import org.brackit.xquery.util.Cfg;
import org.brackit.xquery.util.SequenceUtil;
import org.brackit.xquery.util.dot.DotUtil;
import org.brackit.xquery.xdm.Item;
import org.brackit.xquery.xdm.Sequence;
import org.brackit.xquery.xdm.Tuple;
import org.brackit.xquery.xdm.atomic.Bool;

public final class HadoopExpr implements Expr {

	private static boolean DELETE_EXISTING = Cfg.asBool(XQueryJobConf.PROP_DELETE_EXISTING, false);
	
	private final Configuration conf;
	private final AST ast;
	private final StaticContext sctx;
	
	public HadoopExpr(StaticContext sctx, AST ast, Configuration conf)
	{
		this.conf = conf;
		this.ast = ast;
		this.sctx = sctx;
	}
	
	public Sequence evaluate(QueryContext ctx, Tuple tuple) throws QueryException
	{
		if (!(ctx instanceof HadoopQueryContext)) {
			throw new QueryException(ErrorCode.BIT_DYN_ABORTED_ERROR,
					"Execution of HadoopExpr requires a Hadoop query context");
		}
		
		HadoopQueryContext hctx = (HadoopQueryContext) ctx;
		ShuffleTree sTree = ShuffleTree.build(ast, null);		
		try {
			if (sTree != null) {
				hctx.getClientContext().init(sTree.size());
				trimJob(sTree, null, 0, hctx, tuple);
			}
			else {
				hctx.getClientContext().init(1);
				run(ast, 0, hctx, tuple);
			}
			return new Bool(true);
		}
		catch (IOException e) {
			throw new QueryException(e, ErrorCode.BIT_DYN_ABORTED_ERROR);
		}
	}
	
	private int trimJob(ShuffleTree s, ShuffleTree parent, int seq, QueryContext ctx, Tuple tuple)
			throws IOException, QueryException
	{
		// process all inputs (depth-first)
		int thisSeq = seq;
		while (s.children.size() > 0) {
			seq = trimJob(s.children.get(0), s, seq + 1, ctx, tuple);
		}
		
		// detach job ast from parent job's ast
		AST root = s.shuffle;
		while (root.getParent().getType() != XQExt.Shuffle && root.getType() != XQ.End) {
			root = root.getParent();
		}
		
		if (root.getParent().getType() == XQExt.Shuffle) {
			AST phaseOut = root.copy();
			phaseOut.setProperty("inputSeq", thisSeq);
			root.getParent().replaceChild(root.getChildIndex(), phaseOut);
		}
		else {
			root.getParent().deleteChild(root.getChildIndex());
		}
		
		// remove job from parent
		if (parent != null) {
			parent.children.remove(0);
		}
		
		run (root.copyTree(), thisSeq, ctx, tuple);
		return seq;
	}
	
	private int run(AST root, int seq, QueryContext ctx, Tuple tuple) throws IOException, QueryException
	{
		HadoopQueryContext hctx = (HadoopQueryContext) ctx;
		
		XQueryJobConf jobConf = new XQueryJobConf(conf);
		jobConf.setAst(root);
		jobConf.setStaticContext(sctx);
		jobConf.setSeqNumber(seq);
		if (tuple != null) {
			jobConf.setTuple(tuple);
		}
		jobConf.parseInputsAndOutputs();
		XQueryJob job = new XQueryJob(jobConf);
		job.setJarByClass(HadoopExpr.class);

		
		if (XQuery.DEBUG) {
			DotUtil.drawDotToFile(root.flworDot(), XQuery.DEBUG_DIR, "plan_job" + seq);
		}
		
		if (DELETE_EXISTING) {
			Path outPath = new Path(jobConf.getOutputDir());
			FileSystem fs = outPath.getFileSystem(jobConf);
			if (fs.exists(outPath)) {
				fs.delete(outPath, true);
			}
		}
		
		boolean status;
		try {
			job.submit();
			hctx.getClientContext().attachJob(seq, job.getJobID());
			status = job.waitForCompletion(true);
			if (!status) {
				throw new QueryException(ErrorCode.BIT_DYN_ABORTED_ERROR,
						"Hadoop job execution returned non-zero response");
			}
		} catch (Exception e) {
			throw new IOException(e);
		}
		
		return 0;
	}

	public Item evaluateToItem(QueryContext ctx, Tuple tuple) throws QueryException
	{
		return SequenceUtil.asItem(evaluate(ctx, tuple));
	}

	public boolean isUpdating()
	{
		// TODO allow updates
		return false;
	}

	public boolean isVacuous()
	{
		return false;
	}
	
	private static class ShuffleTree {
		AST shuffle;
		ArrayList<ShuffleTree> children = new ArrayList<ShuffleTree>();

		ShuffleTree(AST shuffle, ShuffleTree parent) {
			this.shuffle = shuffle;
		}
		
		static ShuffleTree build(AST node, ShuffleTree parent)
		{
			if (node == null) {
				return parent;
			}
			if (node.getType() == XQExt.Shuffle) {
				ShuffleTree s = new ShuffleTree(node, parent);
				for (int i = 0; i < node.getChildCount(); i++) {
					build(node.getChild(i), s);
				}
				if (parent != null) {
					parent.children.add(s);
				}
				return s;
			}
			return build(node.getLastChild(), parent);
		}
		
		int size()
		{
			int size = 1;
			for (ShuffleTree child : children) {
				size += child.size();
			}
			return size;
		}
		
	}

}