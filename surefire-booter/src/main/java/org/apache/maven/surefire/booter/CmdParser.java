package org.apache.maven.surefire.booter;

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

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal command line parser to support optional arguments passed to the booter. <br>
 *
 * Expected Format:<br>
 * <pre>
 *     arg1 arg2 -optionalArg=value
 * </pre>
 *
 * @author Matt Coley
 */
public class CmdParser
{
    private static final String OPT_PREFIX = "-";
    private static final String OPT_SPLITTER = ":";
    /**
     * Initial command line arguments string
     */
    private final String[] args;
    /**
     * Map of indexed arguments. Keys are the index.
     */
    private final Map<Integer, String> argsIndexed = new HashMap<Integer, String>();
    /**
     * Map of optional arguments. Keys are the arg name.
     */
    private final Map<String, String> argsOptional = new HashMap<String, String>();

    public CmdParser( String[] args )
    {
        this.args = args;
    }

    public boolean parse()
    {
        int index = 0;
        for ( String arg : args )
        {
            // check optional format
            if ( arg.startsWith( OPT_PREFIX ) )
            {
                String[] parts = arg.substring( OPT_PREFIX.length() ).split( OPT_SPLITTER );
                argsOptional.put( parts[0], parts[1] );
            }
            else
            {
                argsIndexed.put( index++, arg );
            }
        }

        return true;
    }

    /**
     * @return Map of indexed arguments.
     */
    public Map<Integer, String> getArgsIndexed()
    {
        return argsIndexed;
    }

    /**
     * @param key Argument name.
     * @return Optional argument via arg name.
     */
    public String getOptionalArg( String key )
    {
        if ( !argsOptional.containsKey( key ) )
        {
            return null;
        }
        return argsOptional.get( key );
    }

    /**
     * @param key Index.
     * @return Argument via arg index.
     */
    public String getIndexArg( int key )
    {
        if ( !argsIndexed.containsKey( key ) )
        {
            return null;
        }
        return argsIndexed.get( key );
    }
}
