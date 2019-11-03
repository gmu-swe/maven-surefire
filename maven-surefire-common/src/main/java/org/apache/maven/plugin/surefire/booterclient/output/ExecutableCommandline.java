package org.apache.maven.plugin.surefire.booterclient.output;

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

import org.apache.maven.shared.utils.cli.CommandLineCallable;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.apache.maven.shared.utils.cli.Commandline;
import org.apache.maven.shared.utils.cli.StreamConsumer;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.ForkedChannelServer;

import javax.annotation.Nonnull;

/**
 * todo add javadoc
 * @param <T> type of event
 */
public interface ExecutableCommandline<T extends ForkedChannelServer>
{
    @Nonnull CommandLineCallable executeCommandLineAsCallable( @Nonnull Commandline cli,
                                                               @Nonnull T server,
                                                               StreamConsumer stdOut,
                                                               StreamConsumer stdErr,
                                                               @Nonnull Runnable runAfterProcessTermination )
            throws CommandLineException;
}
