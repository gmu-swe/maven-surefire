package org.apache.maven.plugin.surefire.booterclient;

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

import org.apache.maven.plugin.surefire.CommonReflector;
import org.apache.maven.plugin.surefire.StartupReportConfiguration;
import org.apache.maven.plugin.surefire.SurefireProperties;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.AbstractForkInputStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.NotifiableTestStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.OutputStreamFlushableCommandline;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestLessInputStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestProvidingInputStream;
import org.apache.maven.plugin.surefire.booterclient.output.ForkClient;
import org.apache.maven.plugin.surefire.booterclient.output.InPluginProcessDumpSingleton;
import org.apache.maven.plugin.surefire.booterclient.output.NativeStdErrStreamConsumer;
import org.apache.maven.plugin.surefire.booterclient.output.ThreadedStreamConsumer;
import org.apache.maven.plugin.surefire.log.api.ConsoleLogger;
import org.apache.maven.plugin.surefire.report.DefaultReporterFactory;
import org.apache.maven.shared.utils.cli.CommandLineCallable;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.apache.maven.shared.utils.cli.CommandLineTimeOutException;
import org.apache.maven.shared.utils.cli.Commandline;
import org.apache.maven.shared.utils.cli.ShutdownHookUtils;
import org.apache.maven.shared.utils.cli.StreamConsumer;
import org.apache.maven.shared.utils.cli.StreamPumper;
import org.apache.maven.surefire.booter.AbstractPathConfiguration;
import org.apache.maven.surefire.booter.KeyValueSource;
import org.apache.maven.surefire.booter.PropertiesWrapper;
import org.apache.maven.surefire.booter.ProviderConfiguration;
import org.apache.maven.surefire.booter.ProviderFactory;
import org.apache.maven.surefire.booter.Shutdown;
import org.apache.maven.surefire.booter.StartupConfiguration;
import org.apache.maven.surefire.booter.SurefireBooterForkException;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestRequest;
import org.apache.maven.surefire.util.DefaultScanResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.StrictMath.min;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Collections.addAll;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.maven.plugin.surefire.AbstractSurefireMojo.createCopyAndReplaceForkNumPlaceholder;
import static org.apache.maven.plugin.surefire.SurefireHelper.DUMP_FILE_PREFIX;
import static org.apache.maven.plugin.surefire.SurefireHelper.replaceForkThreadsInPath;
import static org.apache.maven.plugin.surefire.booterclient.ForkNumberBucket.drawNumber;
import static org.apache.maven.plugin.surefire.booterclient.ForkNumberBucket.returnNumber;
import static org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestLessInputStream.TestLessInputStreamBuilder;
import static org.apache.maven.shared.utils.cli.ShutdownHookUtils.addShutDownHook;
import static org.apache.maven.shared.utils.cli.ShutdownHookUtils.removeShutdownHook;
import static org.apache.maven.surefire.booter.SystemPropertyManager.writePropertiesFile;
import static org.apache.maven.surefire.cli.CommandLineOption.SHOW_ERRORS;
import static org.apache.maven.surefire.suite.RunResult.*;
import static org.apache.maven.surefire.util.internal.ConcurrencyUtils.countDownToZero;
import static org.apache.maven.surefire.util.internal.DaemonThreadFactory.newDaemonThread;
import static org.apache.maven.surefire.util.internal.DaemonThreadFactory.newDaemonThreadFactory;
import static org.apache.maven.surefire.util.internal.StringUtils.NL;

/**
 * Starts the fork or runs in-process.
 * <br>
 * Lives only on the plugin-side (not present in remote vms)
 * <br>
 * Knows how to fork new vms and also how to delegate non-forking invocation to SurefireStarter directly
 *
 * @author Jason van Zyl
 * @author Emmanuel Venisse
 * @author Brett Porter
 * @author Dan Fabulich
 * @author Carlos Sanchez
 * @author Kristian Rosenvold
 */
public class ForkStarter
{
    private static final String EXECUTION_EXCEPTION = "ExecutionException";

    private static final long PING_IN_SECONDS = 10;

    private static final int TIMEOUT_CHECK_PERIOD_MILLIS = 100;

    private static final ThreadFactory FORKED_JVM_DAEMON_THREAD_FACTORY
        = newDaemonThreadFactory( "surefire-fork-starter" );

    private static final ThreadFactory SHUTDOWN_HOOK_THREAD_FACTORY
        = newDaemonThreadFactory( "surefire-jvm-killer-shutdownhook" );

    private static final AtomicInteger SYSTEM_PROPERTIES_FILE_COUNTER = new AtomicInteger();

    private final ScheduledExecutorService pingThreadScheduler = createPingScheduler();

    private final ScheduledExecutorService timeoutCheckScheduler;

    private final Queue<ForkClient> currentForkClients;

    private final int forkedProcessTimeoutInSeconds;

    private final ProviderConfiguration providerConfiguration;

    private final StartupConfiguration startupConfiguration;

    private final ForkConfiguration forkConfiguration;

    private final StartupReportConfiguration startupReportConfiguration;

    private final ConsoleLogger log;

    private final DefaultReporterFactory defaultReporterFactory;

    private final Collection<DefaultReporterFactory> defaultReporterFactories;

    /**
     * Closes stuff, with a shutdown hook to make sure things really get closed.
     */
    private final class CloseableCloser
        implements Runnable, Closeable
    {
        private final int jvmRun;

        private final Queue<Closeable> testProvidingInputStream;

        private final Thread inputStreamCloserHook;

        CloseableCloser( int jvmRun, Closeable... testProvidingInputStream )
        {
            this.jvmRun = jvmRun;
            this.testProvidingInputStream = new ConcurrentLinkedQueue<>();
            addAll( this.testProvidingInputStream, testProvidingInputStream );
            if ( this.testProvidingInputStream.isEmpty() )
            {
                inputStreamCloserHook = null;
            }
            else
            {
                inputStreamCloserHook = newDaemonThread( this, "closer-shutdown-hook" );
                addShutDownHook( inputStreamCloserHook );
            }
        }

        @Override
        @SuppressWarnings( "checkstyle:innerassignment" )
        public void run()
        {
            for ( Closeable closeable; ( closeable = testProvidingInputStream.poll() ) != null; )
            {
                try
                {
                    closeable.close();
                }
                catch ( IOException e )
                {
                    // This error does not fail a test and does not necessarily mean that the forked JVM std/out stream
                    // was not closed, see ThreadedStreamConsumer. This error means that JVM wrote messages to a native
                    // stream which could not be parsed or report failed. The tests may still correctly run nevertheless
                    // this exception happened => warning on console. The user would see hint to check dump file only
                    // if tests failed, but if this does not happen then printing warning to console is the only way to
                    // inform the users.
                    String msg = "ForkStarter IOException: " + e.getLocalizedMessage() + ".";
                    File reportsDir = defaultReporterFactory.getReportsDirectory();
                    File dump = InPluginProcessDumpSingleton.getSingleton()
                                        .dumpStreamException( e, msg, reportsDir, jvmRun );
                    log.warning( msg + " See the dump file " + dump.getAbsolutePath() );
                }
            }
        }

        @Override
        public void close()
        {
            run();
            if ( inputStreamCloserHook != null )
            {
                removeShutdownHook( inputStreamCloserHook );
            }
        }
    }

    public ForkStarter( ProviderConfiguration providerConfiguration, StartupConfiguration startupConfiguration,
                        ForkConfiguration forkConfiguration, int forkedProcessTimeoutInSeconds,
                        StartupReportConfiguration startupReportConfiguration, ConsoleLogger log )
    {
        this.forkConfiguration = forkConfiguration;
        this.providerConfiguration = providerConfiguration;
        this.forkedProcessTimeoutInSeconds = forkedProcessTimeoutInSeconds;
        this.startupConfiguration = startupConfiguration;
        this.startupReportConfiguration = startupReportConfiguration;
        this.log = log;
        defaultReporterFactory = new DefaultReporterFactory( startupReportConfiguration, log );
        defaultReporterFactory.runStarting();
        defaultReporterFactories = new ConcurrentLinkedQueue<>();
        currentForkClients = new ConcurrentLinkedQueue<>();
        timeoutCheckScheduler = createTimeoutCheckScheduler();
        triggerTimeoutCheck();
    }

    public RunResult run( @Nonnull SurefireProperties effectiveSystemProperties, @Nonnull DefaultScanResult scanResult )
        throws SurefireBooterForkException
    {
        try
        {
            Map<String, String> providerProperties = providerConfiguration.getProviderProperties();
            scanResult.writeTo( providerProperties );
            return isForkOnce()
                    ? run( effectiveSystemProperties, providerProperties )
                    : run( effectiveSystemProperties );
        }
        finally
        {
            defaultReporterFactory.mergeFromOtherFactories( defaultReporterFactories );
            defaultReporterFactory.close();
            pingThreadScheduler.shutdownNow();
            timeoutCheckScheduler.shutdownNow();
        }
    }

    public void killOrphanForks()
    {
        for ( ForkClient fork : currentForkClients )
        {
            fork.kill();
        }
    }

    private RunResult run( SurefireProperties effectiveSystemProperties, Map<String, String> providerProperties )
            throws SurefireBooterForkException
    {
        TestLessInputStreamBuilder builder = new TestLessInputStreamBuilder();
        PropertiesWrapper props = new PropertiesWrapper( providerProperties );
        TestLessInputStream stream = builder.build();
        Thread shutdown = createImmediateShutdownHookThread( builder, providerConfiguration.getShutdown() );
        ScheduledFuture<?> ping = triggerPingTimerForShutdown( builder );
        int forkNumber = drawNumber();
        try
        {
            addShutDownHook( shutdown );
            DefaultReporterFactory forkedReporterFactory =
                    new DefaultReporterFactory( startupReportConfiguration, log, forkNumber );
            defaultReporterFactories.add( forkedReporterFactory );
            ForkClient forkClient =
                    new ForkClient( forkedReporterFactory, stream, log, new AtomicBoolean(), forkNumber );
            return fork( null, props, forkClient, effectiveSystemProperties, forkNumber, stream, false );
        }
        finally
        {
            returnNumber( forkNumber );
            removeShutdownHook( shutdown );
            ping.cancel( true );
            builder.removeStream( stream );
        }
    }

    private RunResult run( SurefireProperties effectiveSystemProperties )
            throws SurefireBooterForkException
    {
        return forkConfiguration.isReuseForks()
                ? runSuitesForkOnceMultiple( effectiveSystemProperties, forkConfiguration.getForkCount() )
                : runSuitesForkPerTestSet( effectiveSystemProperties, forkConfiguration.getForkCount() );
    }

    private boolean isForkOnce()
    {
        return forkConfiguration.isReuseForks() && ( forkConfiguration.getForkCount() == 1 || hasSuiteXmlFiles() );
    }

    private boolean hasSuiteXmlFiles()
    {
        TestRequest testSuiteDefinition = providerConfiguration.getTestSuiteDefinition();
        return testSuiteDefinition != null && !testSuiteDefinition.getSuiteXmlFiles().isEmpty();
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private RunResult runSuitesForkOnceMultiple( final SurefireProperties effectiveSystemProperties, int forkCount )
        throws SurefireBooterForkException
    {
        ThreadPoolExecutor executorService = new ThreadPoolExecutor( forkCount, forkCount, 60, SECONDS,
                                                                  new ArrayBlockingQueue<Runnable>( forkCount ) );
        executorService.setThreadFactory( FORKED_JVM_DAEMON_THREAD_FACTORY );

        final Queue<String> tests = new ConcurrentLinkedQueue<>();

        for ( Class<?> clazz : getSuitesIterator() )
        {
            tests.add( clazz.getName() );
        }

        final Queue<TestProvidingInputStream> testStreams = new ConcurrentLinkedQueue<>();

        for ( int forkNum = 0, total = min( forkCount, tests.size() ); forkNum < total; forkNum++ )
        {
            testStreams.add( new TestProvidingInputStream( tests ) );
        }

        ScheduledFuture<?> ping = triggerPingTimerForShutdown( testStreams );
        Thread shutdown = createShutdownHookThread( testStreams, providerConfiguration.getShutdown() );

        try
        {
            addShutDownHook( shutdown );
            int failFastCount = providerConfiguration.getSkipAfterFailureCount();
            final AtomicInteger notifyStreamsToSkipTestsJustNow = new AtomicInteger( failFastCount );
            final Collection<Future<RunResult>> results = new ArrayList<>( forkCount );
            final AtomicBoolean printedErrorStream = new AtomicBoolean();
            for ( final TestProvidingInputStream testProvidingInputStream : testStreams )
            {
                Callable<RunResult> pf = new Callable<RunResult>()
                {
                    @Override
                    public RunResult call()
                        throws Exception
                    {
                        int forkNumber = drawNumber();
                        DefaultReporterFactory reporter =
                                new DefaultReporterFactory( startupReportConfiguration, log, forkNumber );
                        defaultReporterFactories.add( reporter );
                        ForkClient forkClient = new ForkClient( reporter, testProvidingInputStream, log,
                                printedErrorStream, forkNumber )
                        {
                            @Override
                            protected void stopOnNextTest()
                            {
                                if ( countDownToZero( notifyStreamsToSkipTestsJustNow ) )
                                {
                                    notifyStreamsToSkipTests( testStreams );
                                }
                            }
                        };
                        Map<String, String> providerProperties = providerConfiguration.getProviderProperties();
                        try
                        {
                            return fork( null, new PropertiesWrapper( providerProperties ), forkClient,
                                    effectiveSystemProperties, forkNumber, testProvidingInputStream, true );
                        }
                        finally
                        {
                            returnNumber( forkNumber );
                        }
                    }
                };
                results.add( executorService.submit( pf ) );
            }
            return awaitResultsDone( results, executorService );
        }
        finally
        {
            removeShutdownHook( shutdown );
            ping.cancel( true );
            closeExecutor( executorService );
        }
    }

    private static void notifyStreamsToSkipTests( Collection<? extends NotifiableTestStream> notifiableTestStreams )
    {
        for ( NotifiableTestStream notifiableTestStream : notifiableTestStreams )
        {
            notifiableTestStream.skipSinceNextTest();
        }
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private RunResult runSuitesForkPerTestSet( final SurefireProperties effectiveSystemProperties, int forkCount )
        throws SurefireBooterForkException
    {
        ArrayList<Future<RunResult>> results = new ArrayList<>( 500 );
        ThreadPoolExecutor executorService =
            new ThreadPoolExecutor( forkCount, forkCount, 60, SECONDS, new LinkedBlockingQueue<Runnable>() );
        executorService.setThreadFactory( FORKED_JVM_DAEMON_THREAD_FACTORY );
        final TestLessInputStreamBuilder builder = new TestLessInputStreamBuilder();
        ScheduledFuture<?> ping = triggerPingTimerForShutdown( builder );
        Thread shutdown = createCachableShutdownHookThread( builder, providerConfiguration.getShutdown() );
        try
        {
            addShutDownHook( shutdown );
            int failFastCount = providerConfiguration.getSkipAfterFailureCount();
            final AtomicInteger notifyStreamsToSkipTestsJustNow = new AtomicInteger( failFastCount );
            final AtomicBoolean printedErrorStream = new AtomicBoolean();
            for ( final Class<?> testSet : getSuitesIterator() )
            {
                Callable<RunResult> pf = new Callable<RunResult>()
                {
                    @Override
                    public RunResult call()
                        throws Exception
                    {
                        int forkNumber = drawNumber();
                        DefaultReporterFactory forkedReporterFactory =
                            new DefaultReporterFactory( startupReportConfiguration, log, forkNumber );
                        defaultReporterFactories.add( forkedReporterFactory );
                        ForkClient forkClient = new ForkClient( forkedReporterFactory, builder.getImmediateCommands(),
                                log, printedErrorStream, forkNumber )
                        {
                            @Override
                            protected void stopOnNextTest()
                            {
                                if ( countDownToZero( notifyStreamsToSkipTestsJustNow ) )
                                {
                                    builder.getCachableCommands().skipSinceNextTest();
                                }
                            }
                        };
                        TestLessInputStream stream = builder.build();
                        try
                        {
                            return fork( testSet,
                                         new PropertiesWrapper( providerConfiguration.getProviderProperties() ),
                                         forkClient, effectiveSystemProperties, forkNumber, stream, false );
                        }
                        finally
                        {
                            returnNumber( forkNumber );
                            builder.removeStream( stream );
                        }
                    }
                };
                results.add( executorService.submit( pf ) );
            }
            return awaitResultsDone( results, executorService );
        }
        finally
        {
            removeShutdownHook( shutdown );
            ping.cancel( true );
            closeExecutor( executorService );
        }
    }

    private static RunResult awaitResultsDone( Collection<Future<RunResult>> results, ExecutorService executorService )
        throws SurefireBooterForkException
    {
        RunResult globalResult = new RunResult( 0, 0, 0, 0 );
        SurefireBooterForkException exception = null;
        for ( Future<RunResult> result : results )
        {
            try
            {
                RunResult cur = result.get();
                if ( cur != null )
                {
                    globalResult = globalResult.aggregate( cur );
                }
                else
                {
                    throw new SurefireBooterForkException( "No results for " + result.toString() );
                }
            }
            catch ( InterruptedException e )
            {
                executorService.shutdownNow();
                currentThread().interrupt();
                throw new SurefireBooterForkException( "Interrupted", e );
            }
            catch ( ExecutionException e )
            {
                Throwable realException = e.getCause();
                if ( realException == null )
                {
                    if ( exception == null )
                    {
                        exception = new SurefireBooterForkException( EXECUTION_EXCEPTION );
                    }
                }
                else
                {
                    String previousError = "";
                    if ( exception != null && !EXECUTION_EXCEPTION.equals( exception.getLocalizedMessage().trim() ) )
                    {
                        previousError = exception.getLocalizedMessage() + "\n";
                    }
                    String error = previousError + EXECUTION_EXCEPTION + " " + realException.getLocalizedMessage();
                    exception = new SurefireBooterForkException( error, realException );
                }
            }
        }

        if ( exception != null )
        {
            throw exception;
        }

        return globalResult;
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private void closeExecutor( ExecutorService executorService )
        throws SurefireBooterForkException
    {
        executorService.shutdown();
        try
        {
            // Should stop immediately, as we got all the results if we are here
            executorService.awaitTermination( 60 * 60, SECONDS );
        }
        catch ( InterruptedException e )
        {
            currentThread().interrupt();
            throw new SurefireBooterForkException( "Interrupted", e );
        }
    }

    private RunResult fork( Class<?> testSet, KeyValueSource providerProperties, ForkClient forkClient,
                            SurefireProperties effectiveSystemProperties, int forkNumber,
                            AbstractForkInputStream testProvidingInputStream, boolean readTestsFromInStream )
        throws SurefireBooterForkException
    {
        final String tempDir;
        final File surefireProperties;
        final File systPropsFile;
        try
        {
            tempDir = forkConfiguration.getTempDirectory().getCanonicalPath();
            BooterSerializer booterSerializer = new BooterSerializer( forkConfiguration );
            Long pluginPid = forkConfiguration.getPluginPlatform().getPluginPid();
            surefireProperties = booterSerializer.serialize( providerProperties, providerConfiguration,
                    startupConfiguration, testSet, readTestsFromInStream, pluginPid, forkNumber );

            log.debug( "Determined Maven Process ID " + pluginPid );

            if ( effectiveSystemProperties != null )
            {
                SurefireProperties filteredProperties =
                    createCopyAndReplaceForkNumPlaceholder( effectiveSystemProperties, forkNumber );

                systPropsFile = writePropertiesFile( filteredProperties, forkConfiguration.getTempDirectory(),
                                                     "surefire_" + SYSTEM_PROPERTIES_FILE_COUNTER.getAndIncrement(),
                                                     forkConfiguration.isDebug() );
            }
            else
            {
                systPropsFile = null;
            }
        }
        catch ( IOException e )
        {
            throw new SurefireBooterForkException( "Error creating properties files for forking", e );
        }

        File dumpLogDir = replaceForkThreadsInPath( startupReportConfiguration.getReportsDirectory(), forkNumber );
        OutputStreamFlushableCommandline cli =
                forkConfiguration.createCommandLine( startupConfiguration, forkNumber, dumpLogDir );

        if ( testProvidingInputStream != null )
        {
            testProvidingInputStream.setFlushReceiverProvider( cli );
        }
        // setup server
        ServerSocket serverSocket;
        try
        {
            // auto-assign port randomly
            serverSocket = new ServerSocket( 0 );
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( e );
        }
        //
        // pass arguments to booter
        //
        // index-sensitive arguments
        cli.createArg().setValue( tempDir );
        cli.createArg().setValue( DUMP_FILE_PREFIX + forkNumber );
        cli.createArg().setValue( surefireProperties.getName() );
        cli.createArg().setValue( String.valueOf( serverSocket.getLocalPort() ) );
        // optional arguments
        if ( systPropsFile != null )
        {
            cli.createArg().setValue( "-props:"  + systPropsFile.getName() );
        }
        if ( testSet != null && !forkConfiguration.isReuseForks() && forkConfiguration.getForkCount() == 1 )
        {
            cli.createArg().setValue( "-testClass:"  + testSet.getName() );
        }

        final ThreadedStreamConsumer threadedStreamConsumer = new ThreadedStreamConsumer( forkClient );
        final CloseableCloser closer = new CloseableCloser( forkNumber, threadedStreamConsumer,
                                                            requireNonNull( testProvidingInputStream, "null param" ) );

        log.debug( "Forking command line: " + cli );

        Integer result = null;
        RunResult runResult = null;
        SurefireBooterForkException booterForkException = null;
        try
        {
            NativeStdErrStreamConsumer stdErrConsumer =
                    new NativeStdErrStreamConsumer( forkClient.getDefaultReporterFactory() );

            CommandLineCallable future =
                    executeCommandLineAsCallable2( cli, testProvidingInputStream, threadedStreamConsumer,
                                                        stdErrConsumer, 0, serverSocket, closer, ISO_8859_1 );

            currentForkClients.add( forkClient );

            result = future.call();

            if ( forkClient.hadTimeout() )
            {
                runResult = timeout( forkClient.getDefaultReporterFactory().getGlobalRunStatistics().getRunResult() );
            }
            else if ( result == null || result != SUCCESS )
            {
                booterForkException =
                        new SurefireBooterForkException( "Error occurred in starting fork, check output in log" );
            }
        }
        catch ( IOException | CommandLineException e )
        {
            runResult = failure( forkClient.getDefaultReporterFactory().getGlobalRunStatistics().getRunResult(), e );
            String cliErr = e.getLocalizedMessage();
            Throwable cause = e.getCause();
            booterForkException =
                    new SurefireBooterForkException( "Error while executing forked tests.", cliErr, cause, runResult );
        }
        finally
        {
            currentForkClients.remove( forkClient );
            closer.close();
            if ( runResult == null )
            {
                runResult = forkClient.getDefaultReporterFactory().getGlobalRunStatistics().getRunResult();
            }
            forkClient.close( runResult.isTimeout() );

            if ( !runResult.isTimeout() )
            {
                Throwable cause = booterForkException == null ? null : booterForkException.getCause();
                String detail = booterForkException == null ? "" : "\n" + booterForkException.getMessage();

                if ( forkClient.isErrorInFork() )
                {
                    StackTraceWriter errorInFork = forkClient.getErrorInFork();
                    String errorInForkMessage =
                            errorInFork == null ? null : errorInFork.getThrowable().getLocalizedMessage();
                    boolean showStackTrace = providerConfiguration.getMainCliOptions().contains( SHOW_ERRORS );
                    String stackTrace = errorInForkMessage;
                    if ( showStackTrace )
                    {
                        if ( errorInFork != null )
                        {
                            if ( stackTrace == null )
                            {
                                stackTrace = "";
                            }
                            else
                            {
                                stackTrace += NL;
                            }
                            stackTrace += errorInFork.writeTrimmedTraceToString();
                        }
                    }
                    //noinspection ThrowFromFinallyBlock
                    throw new SurefireBooterForkException( "There was an error in the forked process"
                                                        + detail
                                                        + ( stackTrace == null ? "" : stackTrace ), cause );
                }
                if ( !forkClient.isSaidGoodBye() )
                {
                    String errorCode = result == null ? "" : "\nProcess Exit Code: " + result;
                    String testsInProgress = forkClient.hasTestsInProgress() ? "\nCrashed tests:" : "";
                    for ( String test : forkClient.testsInProgress() )
                    {
                        testsInProgress += "\n" + test;
                    }
                    // noinspection ThrowFromFinallyBlock
                    throw new SurefireBooterForkException(
                        "The forked VM terminated without properly saying goodbye. VM crash or System.exit called?"
                            + "\nCommand was " + cli.toString() + detail + errorCode + testsInProgress, cause );
                }
            }

            if ( booterForkException != null )
            {
                // noinspection ThrowFromFinallyBlock
                throw booterForkException;
            }
        }

        return runResult;
    }

    private Iterable<Class<?>> getSuitesIterator()
        throws SurefireBooterForkException
    {
        try
        {
            AbstractPathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
            ClassLoader unifiedClassLoader = classpathConfiguration.createMergedClassLoader();

            CommonReflector commonReflector = new CommonReflector( unifiedClassLoader );
            Object reporterFactory = commonReflector.createReportingReporterFactory( startupReportConfiguration, log );

            ProviderFactory providerFactory =
                new ProviderFactory( startupConfiguration, providerConfiguration, unifiedClassLoader, reporterFactory );
            SurefireProvider surefireProvider = providerFactory.createProvider( false );
            return surefireProvider.getSuites();
        }
        catch ( SurefireExecutionException e )
        {
            throw new SurefireBooterForkException( "Unable to create classloader to find test suites", e );
        }
    }

    private static Thread createImmediateShutdownHookThread( final TestLessInputStreamBuilder builder,
                                                             final Shutdown shutdownType )
    {
        return SHUTDOWN_HOOK_THREAD_FACTORY.newThread( new Runnable()
        {
            @Override
            public void run()
            {
                builder.getImmediateCommands().shutdown( shutdownType );
            }
        } );
    }

    private static Thread createCachableShutdownHookThread( final TestLessInputStreamBuilder builder,
                                                            final Shutdown shutdownType )
    {
        return SHUTDOWN_HOOK_THREAD_FACTORY.newThread( new Runnable()
        {
            @Override
            public void run()
            {
                builder.getCachableCommands().shutdown( shutdownType );
            }
        } );
    }

    private static Thread createShutdownHookThread( final Iterable<TestProvidingInputStream> streams,
                                                    final Shutdown shutdownType )
    {
        return SHUTDOWN_HOOK_THREAD_FACTORY.newThread( new Runnable()
        {
            @Override
            public void run()
            {
                for ( TestProvidingInputStream stream : streams )
                {
                    stream.shutdown( shutdownType );
                }
            }
        } );
    }

    private static ScheduledExecutorService createPingScheduler()
    {
        ThreadFactory threadFactory = newDaemonThreadFactory( "ping-timer-" + PING_IN_SECONDS + "s" );
        return newScheduledThreadPool( 1, threadFactory );
    }

    private static ScheduledExecutorService createTimeoutCheckScheduler()
    {
        ThreadFactory threadFactory = newDaemonThreadFactory( "timeout-check-timer" );
        return newScheduledThreadPool( 1, threadFactory );
    }

    private ScheduledFuture<?> triggerPingTimerForShutdown( final TestLessInputStreamBuilder builder )
    {
        return pingThreadScheduler.scheduleAtFixedRate( new Runnable()
        {
            @Override
            public void run()
            {
                builder.getImmediateCommands().noop();
            }
        }, 0, PING_IN_SECONDS, SECONDS );
    }

    private ScheduledFuture<?> triggerPingTimerForShutdown( final Iterable<TestProvidingInputStream> streams )
    {
        return pingThreadScheduler.scheduleAtFixedRate( new Runnable()
        {
            @Override
            public void run()
            {
                for ( TestProvidingInputStream stream : streams )
                {
                    stream.noop();
                }
            }
        }, 0, PING_IN_SECONDS, SECONDS );
    }

    private ScheduledFuture<?> triggerTimeoutCheck()
    {
        return timeoutCheckScheduler.scheduleAtFixedRate( new Runnable()
        {
            @Override
            public void run()
            {
                long systemTime = currentTimeMillis();
                for ( ForkClient forkClient : currentForkClients )
                {
                    forkClient.tryToTimeout( systemTime, forkedProcessTimeoutInSeconds );
                }
            }
        }, 0, TIMEOUT_CHECK_PERIOD_MILLIS, MILLISECONDS );
    }

    // ============================================================================= //

    public static CommandLineCallable executeCommandLineAsCallable2(@Nonnull final Commandline cl,
                                                                    @Nullable final InputStream systemIn,
                                                                    final StreamConsumer systemOut,
                                                                    final StreamConsumer systemErr,
                                                                    final int timeoutInSeconds,
                                                                    final ServerSocket serverSocket,
                                                                    @Nullable final Runnable runAfterProcessTermination,
                                                                    @Nullable final Charset streamCharset) throws CommandLineException, IOException
    {
        //noinspection ConstantConditions
        if ( cl == null )
        {
            throw new IllegalArgumentException( "cl cannot be null." );
        }

        final Process p = cl.execute();

        Socket clientSocket = serverSocket.accept();

        final StreamFeeder inputFeeder = systemIn != null ? new StreamFeeder( systemIn, clientSocket.getOutputStream() ) : null;

        final StreamPumper outputPumper = new StreamPumper( clientSocket.getInputStream(), systemOut );

        final StreamPumper errorPumper = new StreamPumper( p.getErrorStream(), systemErr );

        if ( inputFeeder != null )
        {
            inputFeeder.start();
        }

        outputPumper.start();

        errorPumper.start();

        final ProcessHook processHook = new ProcessHook( p );

        ShutdownHookUtils.addShutDownHook( processHook );

        return new CommandLineCallable()
        {
            public Integer call()
                    throws CommandLineException
            {
                try
                {
                    int returnValue;
                    if ( timeoutInSeconds <= 0 )
                    {
                        returnValue = p.waitFor();
                    }
                    else
                    {
                        long now = System.currentTimeMillis();
                        long timeoutInMillis = 1000L * timeoutInSeconds;
                        long finish = now + timeoutInMillis;
                        while ( isAlive( p ) && ( System.currentTimeMillis() < finish ) )
                        {
                            Thread.sleep( 10 );
                        }
                        if ( isAlive( p ) )
                        {
                            throw new InterruptedException(
                                    "Process timeout out after " + timeoutInSeconds + " seconds" );
                        }

                        returnValue = p.exitValue();
                    }

                    if ( runAfterProcessTermination != null )
                    {
                        runAfterProcessTermination.run();
                    }

                    waitForAllPumpers2( inputFeeder, outputPumper, errorPumper );

                    if ( outputPumper.getException() != null )
                    {
                        throw new CommandLineException( "Error inside systemOut parser", outputPumper.getException() );
                    }

                    if ( errorPumper.getException() != null )
                    {
                        throw new CommandLineException( "Error inside systemErr parser", errorPumper.getException() );
                    }

                    return returnValue;
                }
                catch ( InterruptedException ex )
                {
                    if ( inputFeeder != null )
                    {
                        inputFeeder.disable();
                    }

                    outputPumper.disable();
                    errorPumper.disable();
                    throw new CommandLineTimeOutException( "Error while executing external command, process killed.",
                            ex );
                }
                finally
                {
                    ShutdownHookUtils.removeShutdownHook( processHook );

                    processHook.run();

                    if ( inputFeeder != null )
                    {
                        inputFeeder.close();
                    }

                    outputPumper.close();

                    errorPumper.close();

                    try
                    {
                        serverSocket.close();
                    } catch ( Exception e )
                    {
                        // Well, we tried!
                    }
                }
            }
        };
    }

    private static void waitForAllPumpers2(@Nullable StreamFeeder inputFeeder, StreamPumper outputPumper,
                                          StreamPumper errorPumper )
            throws InterruptedException
    {
        if ( inputFeeder != null )
        {
            inputFeeder.waitUntilDone();
        }

        outputPumper.waitUntilDone();
        errorPumper.waitUntilDone();
    }

    private static boolean isAlive( Process p )
    {
        if ( p == null )
        {
            return false;
        }

        try
        {
            p.exitValue();
            return false;
        }
        catch ( IllegalThreadStateException e )
        {
            return true;
        }
    }

    private static class ProcessHook
            extends Thread
    {
        private final Process process;

        private ProcessHook( Process process )
        {
            super( "CommandlineUtils process shutdown hook" );
            this.process = process;
            this.setContextClassLoader( null );
        }

        /** {@inheritDoc} */
        public void run()
        {
            process.destroy();
        }
    }

    /**
     * Read from an InputStream and write the output to an OutputStream.
     *
     * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
     * @version $Id: StreamFeeder.java 1703274 2015-09-15 19:20:31Z tibordigana $
     */
    static class StreamFeeder
            extends AbstractStreamHandler
    {
        private final AtomicReference<InputStream> input;
        private final AtomicReference<OutputStream> output;

        /**
         * Create a new StreamFeeder
         *
         * @param input  Stream to read from
         * @param output Stream to write to
         */
        public StreamFeeder( InputStream input, OutputStream output )
        {
            this.input = new AtomicReference<InputStream>( input );
            this.output = new AtomicReference<OutputStream>( output );
        }

        // ----------------------------------------------------------------------
        // Runnable implementation
        // ----------------------------------------------------------------------

        @Override
        public void run()
        {
            try
            {
                feed();
            }
            catch ( Throwable e )
            {
                // Catch everything so the streams will be closed and flagged as done.
            }
            finally
            {
                close();

                synchronized ( this )
                {
                    notifyAll();
                }
            }
        }

        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        public void close()
        {
            setDone();
            final InputStream is = input.getAndSet( null );
            if ( is != null )
            {
                try
                {
                    is.close();
                }
                catch ( IOException ex )
                {
                    // ignore
                }
            }

            final OutputStream os = output.getAndSet( null );
            if ( os != null )
            {
                try
                {
                    os.close();
                }
                catch ( IOException ex )
                {
                    // ignore
                }
            }
        }

        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        @SuppressWarnings( "checkstyle:innerassignment" )
        private void feed()
                throws IOException
        {
            InputStream is = input.get();
            OutputStream os = output.get();
            if ( is != null && os != null )
            {
                for ( int data; !isDone() && ( data = is.read() ) != -1; )
                {
                    if ( !isDisabled() )
                    {
                        os.write( data );
                    }
                }
            }
        }

    }

    /**
     * @author <a href="mailto:kristian.rosenvold@gmail.com">Kristian Rosenvold</a>
     */
    static class AbstractStreamHandler
            extends Thread
    {
        private volatile boolean done;

        private volatile boolean disabled;

        boolean isDone()
        {
            return done;
        }

        public synchronized void waitUntilDone()
                throws InterruptedException
        {
            while ( !isDone() )
            {
                wait();
            }
        }


        boolean isDisabled()
        {
            return disabled;
        }

        public void disable()
        {
            disabled = true;
        }

        protected void setDone()
        {
            done = true;
        }

    }
}
