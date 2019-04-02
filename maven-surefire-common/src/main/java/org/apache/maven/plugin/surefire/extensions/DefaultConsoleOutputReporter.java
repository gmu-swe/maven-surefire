package org.apache.maven.plugin.surefire.extensions;

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

import org.apache.maven.plugin.surefire.report.ConsoleOutputFileReporter;
import org.apache.maven.plugin.surefire.report.DirectConsoleOutput;
import org.apache.maven.surefire.extensions.ConsoleOutputReportEventListener;
import org.apache.maven.surefire.extensions.ConsoleOutputReporter;

import java.io.File;
import java.io.PrintStream;

/**
 * Extension for logger. This is a builder of {@link ConsoleOutputReportEventListener listeners}. The listeners handle
 * test set events.
 *
 * @author <a href="mailto:tibordigana@apache.org">Tibor Digana (tibor17)</a>
 * @since 3.0.0-M4
 */
public class DefaultConsoleOutputReporter
        extends ConsoleOutputReporter
{
    @Override
    public ConsoleOutputReportEventListener createListener( File reportsDirectory, String reportNameSuffix,
                                                            Integer forkNumber )
    {
        return new ConsoleOutputFileReporter( reportsDirectory, reportNameSuffix, false, forkNumber, getEncoding() );
    }

    @Override
    public ConsoleOutputReportEventListener createListener( PrintStream out, PrintStream err )
    {
        return new DirectConsoleOutput( out, err );
    }

    @Override
    public Object clone( ClassLoader target )
    {
        try
        {
            Class<?> cls = target.loadClass( getClass().getName() );
            Object clone = cls.newInstance();

            cls.getMethod( "setDisable", boolean.class )
                    .invoke( clone, isDisable() );
            cls.getMethod( "setEncoding", String.class )
                    .invoke( clone, getEncoding() );

            return clone;
        }
        catch ( ReflectiveOperationException e )
        {
            throw new IllegalStateException( e.getLocalizedMessage() );
        }
    }

    @Override
    public String toString()
    {
        return "DefaultConsoleOutputReporter{"
                + "disable=" + isDisable()
                + ", encoding=" + getEncoding()
                + '}';
    }
}
