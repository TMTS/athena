/**
 * Copyright 2010 Synaptik Solutions
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 * 
 * @author Dan Watling <dan@synaptik.com>
 */
package com.synaptik.athena;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;


/**
 * TODO: Use an XML parser to retrieve package from Manifest
 * TODO: This code is terribly hokey and was just slapped together. Rewrite and put tests around it.
 * 
 * USAGE: athena <root of your Android test project> <output file [OPTIONAL]>
 * OUTPUTS: If no output file is specified, TEST-all.xml in the current directory.
 * 
 * @author Dan Watling <dan@synaptik.com>
 */
public class Athena {
	public static final String OUTPUT_FILE = "TEST-all.xml";
	
	StringBuilder output;
	String packageName;
	String serial = null;
	String argClassName = null;
	
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("USAGE: athena <root of your Android test project> <output file [default=TEST-all.xml]> <serial number> <class name like: Abc,Bcd>");
		} else {
			new Athena().run(args);
		}
	}
	
	public void run(String[] args) throws Exception {
		System.out.println("\nBegin Athena\n");
		String inputFile = args[0];
		String outputFile = args.length > 1 ? args[1] : null;
		String serialNumber = args.length > 2 ? args[2] : null;
		argClassName  = args.length > 3 ? args[3] : null;
		if(serialNumber == null|| outputFile.length() == 0) {
			serial = null;
		}
		else{
			serial = serialNumber;
		}			
		if (outputFile == null || outputFile.length() == 0) {
			outputFile = OUTPUT_FILE;
		}
		
		FileWriter fw = null;
		try {
			fw = new FileWriter(outputFile);
			File root = new File(inputFile);
			packageName = getPackageNameFromManifest(root);
			
			long start = System.currentTimeMillis();
			fw.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			fw.append("<testsuites>\n");
			List<AthenaTestSuite> testSuites = findAllTestClasses(root);
			
			int totalFailures = 0;
			int totalErrors = 0;
			int totalTests = 0;
			
			System.out.println("Found " + testSuites.size() + " test suites.");
			for (AthenaTestSuite suite : testSuites) {
				if(argClassName!=null){
					if(argClassName.contains(suite.className)){
						System.out.println("Test class "+ suite.className +" matched " + argClassName);
					}
					else{
						System.out.println("Test class "+ suite.className +" un-match " + argClassName + " , continue next...");
						continue;
					}
				}
				System.out.println("Running " + suite.tests.size() + " test(s) for " + suite.className + ": ");
				for (AthenaTest testCase : suite.tests) {
					runCommand(suite, testCase);
				}
				System.out.println();
				totalFailures += suite.getFailures();
				totalErrors += suite.getErrors();
				totalTests += suite.tests.size();
				fw.append(suite.toXML());
			}
			fw.append("</testsuites>\n");
			long end = System.currentTimeMillis();
			
			float pctFail = ((float)totalFailures / (float)totalTests) * 100.0f;
			float pctError = ((float)totalErrors / (float)totalTests) * 100.0f;
			
			System.out.println();
			System.out.println("Total tests: " + totalTests);
			System.out.println("Total failures: " + totalFailures + " (" + pctFail + ")");
			System.out.println("Total errors: " + totalErrors + " (" + pctError + ")");
			System.out.println("Total time: " + (end - start) / 1000 + " seconds");
		} finally {
			if (fw != null) {
				fw.close();
			}
		}
		System.out.println("\nEnd Athena\n");
	}
	
	private String getPackageNameFromManifest(File root) throws IOException {
		File manifest = new File(root.getAbsolutePath() + "/AndroidManifest.xml");
		String result = null;
		if (manifest.exists()) {
			FileReader fr = new FileReader(manifest);
			BufferedReader br = new BufferedReader(fr);
			String line = br.readLine();
			while (line != null) {
				if (line.contains("package")) {
					result = line.trim();
					result = result.substring(result.indexOf("package")+9);
					result = result.substring(0, result.indexOf("\""));
					break;
				}
				line = br.readLine();
			}
		}
		return result;
	}
	
	private List<AthenaTestSuite> findAllTestClasses(File root) throws IOException {
		List<AthenaTestSuite> result = new ArrayList<AthenaTestSuite>();
		
		File[] files = root.listFiles();
		for (int i = 0; i < files.length; i ++) {
			if (files[i].isDirectory()) {
				result.addAll(findAllTestClasses(files[i]));
			} else {
				if (files[i].getName().endsWith("Test.java")) {
					String className = files[i].getAbsolutePath();
					className = className.substring(className.indexOf("src")+4);
					className = className.replaceAll("\\\\", "\\.");
					className = className.substring(0, className.lastIndexOf(".java"));
					
					AthenaTestSuite suite = new AthenaTestSuite(className);
					suite.populateTests(files[i]);
					result.add(suite);
				}
			}
		}
		
		return result;
	}
	
	private void runCommand(AthenaTestSuite suite, AthenaTest testCase) throws Exception {
		String before;
		if(serial == null){
			before = "adb shell am instrument -w -e class ";
		}
		else{
			before = "adb -s "+serial+" shell am instrument -w -e class ";
		}
		String cmd = before + suite.className + "#" + testCase.name + " " + packageName + "/android.test.InstrumentationTestRunner";
		
		System.out.println(cmd);
		
		testCase.result.start = System.currentTimeMillis();
		Process p = Runtime.getRuntime().exec(cmd);
		
		StreamCaptureThread errorStream = new StreamCaptureThread(p.getErrorStream()); 
		StreamCaptureThread outputStream = new StreamCaptureThread(p.getInputStream()); 
		new Thread(errorStream).start();
		new Thread(outputStream).start();
		p.waitFor();
		
		
		testCase.result.testName = testCase.name;
		testCase.result.parse(outputStream.output.toString(), errorStream.output.toString());
		
		if (testCase.result.failure != null) {
			System.out.print("F");
		} else if (testCase.result.error != null) {
			System.out.print("E");
		} else {
			System.out.print(".");
		}
	}
	
	public class StreamCaptureThread implements Runnable {
		InputStream stream;
		StringBuilder output;
		
		public StreamCaptureThread(InputStream stream) {
			this.stream = stream;
			this.output = new StringBuilder();
		}
		
		public void run() {
			try {
				try {
					BufferedReader br = new BufferedReader(new InputStreamReader(this.stream));
					String line = br.readLine();
					while (line != null) {
						if (line.trim().length() > 0) {
							output.append(line).append("\n");
						}
						line = br.readLine();
					}
					
				} finally {
					if (stream != null) { stream.close(); }
				}
			} catch (IOException ex) {
				ex.printStackTrace(System.err);
			}
		}
	}
}