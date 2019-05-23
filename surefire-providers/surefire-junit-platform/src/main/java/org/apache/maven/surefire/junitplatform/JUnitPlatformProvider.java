package org.apache.maven.surefire.junitplatform;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.surefire.providerapi.AbstractProvider;
import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.report.ConsoleOutputReceiver;
import org.apache.maven.surefire.report.ReporterException;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.ScanResult;
import org.apache.maven.surefire.util.TestsToRun;
import org.junit.platform.commons.util.StringUtils;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.logging.Logger;

import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.toList;
import static org.apache.maven.surefire.booter.ProviderParameterNames.TESTNG_EXCLUDEDGROUPS_PROP;
import static org.apache.maven.surefire.booter.ProviderParameterNames.TESTNG_GROUPS_PROP;
import static org.apache.maven.surefire.report.ConsoleOutputCapture.startCapture;
import static org.apache.maven.surefire.util.TestsToRun.fromClass;
import static org.junit.platform.commons.util.StringUtils.isBlank;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * JUnit 5 Platform Provider.
 *
 * @since 2.22.0
 */
public class JUnitPlatformProvider
    extends AbstractProvider
{
    static final String CONFIGURATION_PARAMETERS = "configurationParameters";

    private final ProviderParameters parameters;

    private final Launcher launcher;

    private final Filter<?>[] filters;

    private final Map<String, String> configurationParameters;

    public JUnitPlatformProvider( ProviderParameters parameters )
    {
        this( parameters, LauncherFactory.create() );
    }

    JUnitPlatformProvider( ProviderParameters parameters, Launcher launcher )
    {
        this.parameters = parameters;
        this.launcher = launcher;
        filters = newFilters();
        configurationParameters = newConfigurationParameters();
        Logger.getLogger( "org.junit" ).setLevel( WARNING );
    }

    @Override
    public Iterable<Class<?>> getSuites()
    {
        return scanClasspath();
    }

    @Override
    public RunResult invoke( Object forkTestSet )
                    throws TestSetFailedException, ReporterException
    {
        ReporterFactory reporterFactory = parameters.getReporterFactory();
        final RunResult runResult;
        try
        {
            RunListener runListener = reporterFactory.createReporter();
            startCapture( ( ConsoleOutputReceiver ) runListener );
            if ( forkTestSet instanceof TestsToRun )
            {
                invokeAllTests( (TestsToRun) forkTestSet, runListener );
            }
            else if ( forkTestSet instanceof Class )
            {
                invokeAllTests( fromClass( ( Class<?> ) forkTestSet ), runListener );
            }
            else if ( forkTestSet == null )
            {
                invokeAllTests( scanClasspath(), runListener );
            }
            else
            {
                throw new IllegalArgumentException(
                        "Unexpected value of forkTestSet: " + forkTestSet );
            }
        }
        finally
        {
            runResult = reporterFactory.close();
        }
        return runResult;
    }

    private TestsToRun scanClasspath()
    {
        TestPlanScannerFilter filter = new TestPlanScannerFilter( launcher, filters );
        ScanResult scanResult = parameters.getScanResult();
        TestsToRun scannedClasses = scanResult.applyFilter( filter, parameters.getTestClassLoader() );
        return parameters.getRunOrderCalculator().orderTestClasses( scannedClasses );
    }

    private void invokeAllTests( TestsToRun testsToRun, RunListener runListener ) {
        LauncherDiscoveryRequest discoveryRequest = buildLauncherDiscoveryRequest(testsToRun);
        RunListenerAdapter adapter = new RunListenerAdapter(runListener);
        launcher.execute(discoveryRequest, adapter);
        // Rerun failing tests if requested
        int count = parameters.getTestRequest().getRerunFailingTestsCount();
        if ( count > 0 && adapter.hasFailingTests() ) {
            for ( int i = 0; i < count; i++ ) {
                // Rerun tests.
                Set<Class<?>> failed = getFailureSet( testsToRun, adapter );
                TestsToRun failedTestsToRun = new TestsToRun( failed );
                discoveryRequest = buildLauncherDiscoveryRequest( failedTestsToRun );
                launcher.execute( discoveryRequest, adapter );
                // If no tests fail in the rerun, we're done
                if ( !adapter.hasFailingTests() ) {
                    break;
                }
            }
        }
    }

    /**
     * @param testsToRun Original TestsToRun instance.
     * @param adapter    Adapter containing failing tests.
     * @return Set of classes to supply for a new TestsToRun.
     */
    private Set<Class<?>> getFailureSet(TestsToRun testsToRun, RunListenerAdapter adapter) {
        Map<TestIdentifier, TestExecutionResult> failures = getFailingTests( adapter );
        // Lookup of class names to class instances
        Map<String, Class<?>> testLookup = new HashMap<>();
        testsToRun.forEach(c -> testLookup.put(c.getName(), c));
        // Filter classes of original TestsToRun by failed classes
        Set<Class<?>> failed = new HashSet<>();
        for (TestIdentifier testIdentifier : failures.keySet()) {
            String[] classMethodName = adapter.toClassMethodName( testIdentifier );
            String className = classMethodName[1];
            failed.add(testLookup.get(className));
        }
        return failed;
    }

    private Map<TestIdentifier,TestExecutionResult> getFailingTests(RunListenerAdapter adapter) {
        // copy results
        Map<TestIdentifier,TestExecutionResult> copy = new HashMap<>(adapter.getFailures());
        // remove results from the adapter so a re-execution can repopulate a fresh map
        adapter.getFailures().clear();
        return copy;
    }

    private LauncherDiscoveryRequest buildLauncherDiscoveryRequest( TestsToRun testsToRun )
    {
        LauncherDiscoveryRequestBuilder builder =
                        request().filters( filters ).configurationParameters( configurationParameters );
        for ( Class<?> testClass : testsToRun )
        {
            builder.selectors( selectClass( testClass ) );
        }
        return builder.build();
    }

    private Filter<?>[] newFilters()
    {
        List<Filter<?>> filters = new ArrayList<>();

        getPropertiesList( TESTNG_GROUPS_PROP )
                .map( TagFilter::includeTags )
                .ifPresent( filters::add );

        getPropertiesList( TESTNG_EXCLUDEDGROUPS_PROP )
                .map( TagFilter::excludeTags )
                .ifPresent( filters::add );

        TestListResolver testListResolver = parameters.getTestRequest().getTestListResolver();
        if ( !testListResolver.isEmpty() )
        {
            filters.add( new TestMethodFilter( testListResolver ) );
        }

        return filters.toArray( new Filter<?>[ filters.size() ] );
    }

    Filter<?>[] getFilters()
    {
        return filters;
    }

    private Map<String, String> newConfigurationParameters()
    {
        String content = parameters.getProviderProperties().get( CONFIGURATION_PARAMETERS );
        if ( content == null )
        {
            return emptyMap();
        }
        try ( StringReader reader = new StringReader( content ) )
        {
            Map<String, String> result = new HashMap<>();
            Properties props = new Properties();
            props.load( reader );
            props.stringPropertyNames()
                    .forEach( key -> result.put( key, props.getProperty( key ) ) );
            return result;
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( "Error reading " + CONFIGURATION_PARAMETERS, e );
        }
    }

    Map<String, String> getConfigurationParameters()
    {
        return configurationParameters;
    }

    private Optional<List<String>> getPropertiesList( String key )
    {
        String property = parameters.getProviderProperties().get( key );
        return isBlank( property ) ? empty()
                        : of( stream( property.split( "[,]+" ) )
                                              .filter( StringUtils::isNotBlank )
                                              .map( String::trim )
                                              .collect( toList() ) );
    }
}
