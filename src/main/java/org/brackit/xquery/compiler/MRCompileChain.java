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
package org.brackit.xquery.compiler;

import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.brackit.hadoop.job.XQueryJobConf;
import org.brackit.xquery.QueryException;
import org.brackit.xquery.XQuery;
import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.atomic.Str;
import org.brackit.xquery.compiler.analyzer.Analyzer;
import org.brackit.xquery.compiler.analyzer.PrologAnalyzer;
import org.brackit.xquery.compiler.optimizer.MROptimizer;
import org.brackit.xquery.compiler.optimizer.Optimizer;
import org.brackit.xquery.compiler.translator.MRTranslator;
import org.brackit.xquery.module.StaticContext;
import org.brackit.xquery.util.dot.DotUtil;
import org.brackit.xquery.xdm.Expr;


public class MRCompileChain extends CompileChain {

	public MRCompileChain()
	{
		CompileChain.BOTTOM_UP_PLAN = true;
	}
	
	@Override
	protected Optimizer getOptimizer(Map<QNm, Str> options)
	{
		return new MROptimizer(options);
	}

	@Override
	public Expr compile(String query) throws QueryException {
		if (XQuery.DEBUG) {
			System.out.println(String.format("Compiling:\n%s", query));
		}
		
		// PARSE
		ModuleResolver resolver = getModuleResolver();
		AST parsed = parse(query);
		if (XQuery.DEBUG) {
			DotUtil.drawDotToFile(parsed.dot(), XQuery.DEBUG_DIR, "parsed");
		}
		
		// ANALYZE
		PrologAnalyzer.collectionFactory = new HadoopCollectionFactory();
		Analyzer analyzer = new Analyzer(resolver, baseURI);
		Targets targets = analyzer.analyze(parsed);		
		Map<QNm, Str> options = targets.getStaticContext().getOptions();

		// OPTIMIZE
		targets.optimize(getOptimizer(options));
		if (XQuery.DEBUG) {
			DotUtil.drawDotToFile(parsed.dot(), XQuery.DEBUG_DIR, "xquery");
		}
		
		StaticContext sctx = targets.getStaticContext();
		Target body = targets.removeBodyTarget();
		XQueryJobConf conf = new XQueryJobConf(new Configuration());
		conf.setTargets(targets);
		
		// add compiled module to library
		if (analyzer.getTargetNS() != null) {
			resolver.register(analyzer.getTargetNS(), sctx);
		}
		
		MRTranslator translator = new MRTranslator(conf, options);
		return translator.expression(sctx, body.getAst(), false);
	}
	
	

}
