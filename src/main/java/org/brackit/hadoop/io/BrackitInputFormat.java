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
package org.brackit.hadoop.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.util.ReflectionUtils;
import org.brackit.hadoop.job.XQueryJobConf;

public class BrackitInputFormat<K, V> extends InputFormat<K, V> {

	@Override
	public RecordReader<K, V> createRecordReader(InputSplit split, TaskAttemptContext context)
		throws IOException, InterruptedException
	{
		return new BrackitRecordReader<K,V>(split, context);
	}

	@Override
	public List<InputSplit> getSplits(JobContext context) throws IOException, InterruptedException
	{
		ArrayList<InputSplit> result = new ArrayList<InputSplit>();
		XQueryJobConf conf = new XQueryJobConf(context.getConfiguration());
		
		List<Class<? extends InputFormat<?, ?>>> formats = conf.getInputFormats();
		String[] paths = null;
		int pathIndex = 0;
		for (int i = 0; i < formats.size(); i++) {
			InputFormat<?,?> format = ReflectionUtils.newInstance(formats.get(i), context.getConfiguration());
			if (FileInputFormat.class.isAssignableFrom(formats.get(i))) {
				// workaround for static paths on FileInputFormat
				if (paths == null) {
					paths = conf.getInputPaths();
				}
				// look for splits of file in paths[pathIndex] only 
				for (InputSplit split : format.getSplits(context)) {
					String pathStr = new Path(paths[pathIndex]).toUri().getPath();
					String splitPath = ((FileSplit) split).getPath().toUri().getPath();
					if (splitPath.indexOf(pathStr) > -1) {
						result.add(new BrackitInputSplit(split, formats.get(i), i, conf));
					}
				}
				pathIndex++;
			}
			else {
				for (InputSplit split : format.getSplits(context)) {
					result.add(new BrackitInputSplit(split, formats.get(i), i, context.getConfiguration()));
				}
			}
		}
		
		return result;
	}
	
}
