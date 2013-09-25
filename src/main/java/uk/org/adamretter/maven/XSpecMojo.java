/**
 * Copyright © 2013, Adam Retter
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.org.adamretter.maven;


import net.sf.saxon.s9api.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.*;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Goal which runs any XSpec tests in src/test/xspec
 *
 * @author Adam Retter <adam.retter@googlemail.com>
 *
 * @requiresDependencyResolution test
 * @goal run-xspec
 * @phase verify
 */
public class XSpecMojo extends AbstractMojo implements LogProvider {

    //TODO remove
    public static void main(String[] args) throws MojoExecutionException {
        final XSpecMojo mojo = new XSpecMojo();
        mojo.setLog(new SystemStreamLog());
        mojo.setXspecCompiler("/opt/xspec/src/compiler/generate-xspec-tests.xsl");
        mojo.setXspecReporter("/opt/xspec/src/reporter/format-xspec-report.xsl");
        mojo.setTestDir(new File("/home/dev/svn-root/trunk/transformations/src/test/xspec"));
        mojo.setReportDir(new File("/tmp/xspec-maven-plugin"));

        mojo.execute();
    }


    /** @parameter expression="${skipTests}" default-value="false" */
    private boolean skipTests;

    /**
     * Location of the XSpec Compiler XSLT i.e.generate-xspec-tests.xsl
     *
     * @parameter expression="${compilerXsl}" default-value="xspec/src/compiler/generate-xspec-tests.xsl"
     */
    private String xspecCompiler;

    /**
     * Location of the XSpec Reporter XSLT i.e. format-xspec-report.xsl
     *
     * @parameter expression="${reporterXsl}" default-value="xspec/src/reporter/format-xspec-report.xsl"
     */
    private String xspecReporter;

    /**
     * Location of the XSpec tests
     * @parameter expression="${testDir}" default-value="${basedir}/src/test/xspec"
     */
    private File testDir;

    /**
     * Location of the XSpec reports
     * @parameter expression="${reportDir}" default-value="${project.build.directory}/xspec-reports"
     */
    private File reportDir;


    private final static SAXParserFactory parserFactory = SAXParserFactory.newInstance();
    private final static Processor processor = new Processor(false);
    private final static XsltCompiler xsltCompiler = processor.newXsltCompiler();


    public void execute() throws MojoExecutionException {

        if(isSkipTests()) {
            getLog().info("'skipTests' is set... skipping XSpec tests!");
            return;
        }

        final ResourceResolver resourceResolver = new ResourceResolver(this);

        final String compilerPath = getXspecCompiler();
        getLog().debug("Using XSpec Compiler: " + compilerPath);

        final String reporterPath = getXspecReporter();
        getLog().debug("Using XSpec Reporter: " + reporterPath);

        InputStream isCompiler = null;
        InputStream isReporter = null;
        try {

            isCompiler = resourceResolver.getResource(compilerPath);
            if(isCompiler == null) {
                throw new MojoExecutionException("Could not find XSpec Compiler stylesheets in: " + compilerPath);
            }

            isReporter = resourceResolver.getResource(reporterPath);
            if(isReporter == null) {
                throw new MojoExecutionException("Could not find XSpec Reporter stylesheets in: " + reporterPath);
            }

            final Source srcCompiler = new StreamSource(isCompiler);
            srcCompiler.setSystemId(compilerPath);
            final XsltExecutable xeCompiler = xsltCompiler.compile(srcCompiler);
            final XsltTransformer xtCompiler = xeCompiler.load();

            final Source srcReporter = new StreamSource(isReporter);
            srcReporter.setSystemId(reporterPath);
            final XsltExecutable xeReporter = xsltCompiler.compile(srcReporter);
            final XsltTransformer xtReporter = xeReporter.load();

            getLog().debug("Looking for XSpecs in: " + getTestDir());
            final List<File> xspecs = findAllXSpecs(getTestDir());
            getLog().info("Found " + xspecs.size() + " XSpecs...");

            boolean failed = false;
            for(final File xspec : xspecs) {
                if(!processXSpec(xspec, xtCompiler, xtReporter)) {
                    failed = true;
                }
            }

            if(failed) {
                throw new MojoExecutionException("Some XSpec tests failed or were missed!");
            }

        } catch(final SaxonApiException sae) {
            getLog().error("Unable to compile the XSpec Compiler: " + compilerPath);
            throw new MojoExecutionException(sae.getMessage(), sae);
        } finally {
            if(isCompiler != null) {
                try { isCompiler.close(); } catch(final IOException ioe) { getLog().warn(ioe); };
            }
        }
    }

    /**
     * Process an XSpec Test
     *
     * @param xspec The path to the XSpec test file
     * @param compiler A transformer for the XSpec compiler
     * @param reporter A transformer for the XSpec reporter
     *
     * @return true if all tests in XSpec pass, false otherwise
     */
    final boolean processXSpec(final File xspec, final XsltTransformer compiler, final XsltTransformer reporter) {
        getLog().info("Processing XSpec: " + xspec.getAbsolutePath());

        /* compile the test stylesheet */
        final CompiledXSpec compiledXSpec = compileXSpec(compiler, xspec);
        if(compiledXSpec == null) {
            return false;
        } else {
            /* execute the test stylesheet */
            final XSpecResultsHandler resultsHandler = new XSpecResultsHandler();
            try {
                final XsltExecutable xeXSpec = xsltCompiler.compile(new StreamSource(compiledXSpec.getCompiledStylesheet()));
                final XsltTransformer xtXSpec = xeXSpec.load();
                xtXSpec.setInitialTemplate(QName.fromClarkName("{http://www.jenitennison.com/xslt/xspec}main"));

                getLog().info("Executing XSpec: " + compiledXSpec);

                //setup xml report output
                final File xspecXmlResult = getXSpecXmlResultPath(getReportDir(), xspec);
                final Serializer xmlSerializer = new Serializer();
                xmlSerializer.setOutputProperty(Serializer.Property.METHOD, "xml");
                xmlSerializer.setOutputProperty(Serializer.Property.INDENT, "yes");
                xmlSerializer.setOutputFile(xspecXmlResult);

                //setup html report output
                final File xspecHtmlResult = getXSpecHtmlResultPath(getReportDir(), xspec);
                final Serializer htmlSerializer = new Serializer();
                htmlSerializer.setOutputProperty(Serializer.Property.METHOD, "html");
                htmlSerializer.setOutputProperty(Serializer.Property.INDENT, "yes");
                htmlSerializer.setOutputFile(xspecHtmlResult);
                reporter.setDestination(htmlSerializer);

                //execute
                final Destination destination = new TeeDestination(new TeeDestination(new SAXDestination(resultsHandler), xmlSerializer), reporter);
                xtXSpec.setDestination(destination);
                xtXSpec.transform();

            } catch(final SaxonApiException te) {
                getLog().error(te);
            }

            //missed tests come about when the XSLT processor aborts processing the XSpec due to an XSLT error
            final int missed = compiledXSpec.getTests() - resultsHandler.getTests();

            //report results
            final String msg = String.format("%s results [Total/Passed/Failed/Missed] = [%d/%d/%d/%d]", xspec.getName(), compiledXSpec.getTests(), resultsHandler.getPassed(), resultsHandler.getFailed(), missed);
            if(resultsHandler.getFailed() + missed > 0) {
                getLog().error(msg);
                return false;
            } else {
                getLog().info(msg);
                return true;
            }
        }
    }

    /**
     * Compiles an XSpec using the provided XSLT XSpec compiler
     *
     * @param compiler The XSpec XSLT compiler
     * @param xspec The XSpec test to compile
     *
     * @return Details of the Compiled XSpec or null if the XSpec could not be compiled
     */
    final CompiledXSpec compileXSpec(final XsltTransformer compiler, final File xspec) {

        InputStream isXSpec = null;
        try {
            final File compiledXSpec = getCompiledXSpecPath(getReportDir(), xspec);
            getLog().info("Compiling XSpec to XSLT: " + compiledXSpec);

            isXSpec = new FileInputStream(xspec);

            final SAXParser parser = parserFactory.newSAXParser();
            final XMLReader reader = parser.getXMLReader();
            final XSpecTestFilter xspecTestFilter = new XSpecTestFilter(reader);

            final InputSource inXSpec = new InputSource(isXSpec);
            inXSpec.setSystemId(xspec.getAbsolutePath());

            compiler.setSource(new SAXSource(xspecTestFilter, inXSpec));

            final Serializer serializer = new Serializer();
            serializer.setOutputFile(compiledXSpec);
            compiler.setDestination(serializer);

            compiler.transform();

            return new CompiledXSpec(xspecTestFilter.getTests(), compiledXSpec);

        } catch(final SaxonApiException sae) {
            getLog().error(sae);
        } catch(final ParserConfigurationException pce) {
            getLog().error(pce);
        } catch(SAXException saxe) {
            getLog().error(saxe);
        } catch(final FileNotFoundException fnfe) {
            getLog().error(fnfe);
        } finally {
            if(isXSpec != null) {
                try { isXSpec.close(); } catch(final IOException ioe) { getLog().warn(ioe); };
            }
        }

        return null;
    }

    /**
     * Get location for Compiled XSpecs
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     *
     * @return A filepath to place the compiled XSpec in
     */
    final File getCompiledXSpecPath(final File xspecReportDir, final File xspec) {
        final File fCompiledDir = new File(xspecReportDir, "xslt");
        if(!fCompiledDir.exists()) {
            fCompiledDir.mkdirs();
        }
        return new File(fCompiledDir, xspec.getName() + ".xslt");
    }

    /**
     * Get location for XSpec test report (XML Format)
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     *
     * @return A filepath for the report
     */
    final File getXSpecXmlResultPath(final File xspecReportDir, final File xspec) {
        return getXSpecResultPath(xspecReportDir, xspec, "xml");
    }

    /**
     * Get location for XSpec test report (HTML Format)
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     *
     * @return A filepath for the report
     */
    final File getXSpecHtmlResultPath(final File xspecReportDir, final File xspec) {
        return getXSpecResultPath(xspecReportDir, xspec, "html");
    }

    /**
     * Get location for XSpec test report
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     * @param extension Filename extension for the report (excluding the '.'
     *
     * @return A filepath for the report
     */
    final File getXSpecResultPath(final File xspecReportDir, final File xspec, final String extension) {
        if(!xspecReportDir.exists()) {
            xspecReportDir.mkdirs();
        }
        return new File(xspecReportDir, xspec.getName().replace(".xspec", "") + "." + extension);
    }

    /**
     * Recursively find any files whoose name ends '.xspec'
     * under the directory xspecTestDir
     *
     * @param xspecTestDir The directory to search for XSpec files
     *
     * @return List of XSpec files
     */
    private List<File> findAllXSpecs(final File xspecTestDir) {

        final List<File> specs = new ArrayList<File>();

        final File[] specFiles = xspecTestDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(final File file) {
                return file.isFile() && file.getName().endsWith(".xspec");
            }
        });
        specs.addAll(Arrays.asList(specFiles));

        for(final File subDir : xspecTestDir.listFiles(new FileFilter(){
            @Override
            public boolean accept(final File file) {
                return file.isDirectory();
            }
        })){
            specs.addAll(findAllXSpecs(subDir));
        }

        return specs;
    }


    //TODO remove
    public void setSkipTests(boolean skipTests) {
        this.skipTests = skipTests;
    }

    public void setXspecCompiler(String xspecCompiler) {
        this.xspecCompiler = xspecCompiler;
    }

    public void setXspecReporter(String xspecReporter) {
        this.xspecReporter = xspecReporter;
    }

    public void setReportDir(File reportDir) {
        this.reportDir = reportDir;
    }

    public void setTestDir(File testDir) {
        this.testDir = testDir;
    }

    protected boolean isSkipTests() {
        return skipTests;
    }

    protected String getXspecCompiler() {
        return xspecCompiler;
    }

    protected String getXspecReporter() {
        return xspecReporter;
    }

    protected File getReportDir() {
        return reportDir;
    }

    protected File getTestDir() {
        return testDir;
    }
}