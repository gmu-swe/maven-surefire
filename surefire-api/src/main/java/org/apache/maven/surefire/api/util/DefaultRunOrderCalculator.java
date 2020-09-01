package org.apache.maven.surefire.api.util;

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

import org.apache.maven.surefire.api.runorder.RunEntryStatisticsMap;
import org.apache.maven.surefire.api.testset.RunOrderParameters;
import org.apache.maven.surefire.util.MethodRunOrder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Applies the final runorder of the tests
 *
 * @author Kristian Rosenvold
 */
public class DefaultRunOrderCalculator
    implements RunOrderCalculator
{
    private final Comparator<Class> sortOrder;

    private final RunOrder[] runOrder;

    private final RunOrderParameters runOrderParameters;

    private final int threadCount;

    private final Random random;


    public DefaultRunOrderCalculator( RunOrderParameters runOrderParameters, int threadCount )
    {
        this.runOrderParameters = runOrderParameters;
        this.threadCount = threadCount;
        this.runOrder = runOrderParameters.getRunOrder();
        this.sortOrder = this.runOrder.length > 0 ? getSortOrderComparator( this.runOrder[0] ) : null;
        this.random = new Random( runOrderParameters.getRandomSeed() );
    }

    @Override
    @SuppressWarnings( "checkstyle:magicnumber" )
    public TestsToRun orderTestClasses( TestsToRun scannedClasses )
    {
        List<Class<?>> result = new ArrayList<>( 512 );

        for ( Class<?> scannedClass : scannedClasses )
        {
            result.add( scannedClass );
        }

        orderTestClasses( result, runOrder.length != 0 ? runOrder[0] : null );
        return new TestsToRun( new LinkedHashSet<>( result ) );
    }

    @Override
    public Comparator<String> comparatorForTestMethods()
    {
        MethodRunOrder order = runOrderParameters.getMethodRunOrder();
        if ( MethodRunOrder.DEFAULT.equals( order ) )
        {
            return null;
        }
        else if ( MethodRunOrder.ALPHABETICAL.equals( order ) )
        {
            return new Comparator<String>()
            {
                @Override
                public int compare( String o1, String o2 )
                {
                    return o1.compareTo( o2 );
                }
            };
        }
        else if ( MethodRunOrder.REVERSE_ALPHABETICAL.equals( order ) )
        {
            return new Comparator<String>()
            {
                @Override
                public int compare( String o1, String o2 )
                {
                    return o2.compareTo( o1 );
                }
            };
        }
        else if ( MethodRunOrder.RANDOM.equals( order ) )
        {
            return new Comparator<String>()
            {
                HashMap<String, Integer> randomVals = new HashMap<>();

                private int getRandom( String obj )
                {
                    if ( !randomVals.containsKey( obj ) )
                    {
                        randomVals.put( obj, random.nextInt() );
                    }
                    return randomVals.get( obj );
                }

                @Override
                public int compare( String o1, String o2 )
                {
                    int i1 = getRandom( o1 );
                    int i2 = getRandom( o2 );
                    return ( i1 > i2 ? 1 : -1 );
                }
            };
        }
        else if ( MethodRunOrder.FLAKY_FINDING.equals( order ) )
        {
            String orderParam = System.getProperty( "flakyTestOrder" );
            if ( orderParam == null )
            {
                throw new IllegalStateException( "Please set system property flakyTestOrder to use flaky finding" );
            }
            final HashMap<String, Integer> orders = new HashMap<>();
            int i = 0;
            for ( String s : orderParam.split( "," ) )
            {
                orders.put( s, i );
                i++;
                if ( i > 2 )
                {
                    throw new UnsupportedOperationException( "This only supports 2 tests at a time for now." );
                }
            }
            return new Comparator<String>()
            {
                int getRank( String o )
                {
                    synchronized ( orders )
                    {
                        if ( !orders.containsKey( o ) )
                        {
                            orders.put( o, orders.size() );
                        }
                        return orders.get( o );
                    }
                }

                @Override
                public int compare( String o1, String o2 )
                {
                    int r1 = getRank( o1 );
                    int r2 = getRank( o2 );
                    if ( r1 < r2 )
                    {
                        return -1;
                    }
                    else
                    {
                        return 1;
                    }
                }
            };
        }
        else
        {
            throw new UnsupportedOperationException( "Unsupported method run order: " + order.name() );
        }
    }

    private void orderTestClasses( List<Class<?>> testClasses, RunOrder runOrder )
    {
        if ( System.getProperty( "flakyTestOrder" ) != null )
        {
            List<Class<?>> sorted = sortClassesBySpecifiedOrder( testClasses, System.getProperty( "flakyTestOrder" ) );
            testClasses.clear();
            testClasses.addAll( sorted );
        }
        else if ( RunOrder.RANDOM.equals( runOrder ) )
        {
            Collections.shuffle( testClasses, random );
        }
        else if ( RunOrder.FAILEDFIRST.equals( runOrder ) )
        {
            RunEntryStatisticsMap stat = RunEntryStatisticsMap.fromFile( runOrderParameters.getRunStatisticsFile() );
            List<Class<?>> prioritized = stat.getPrioritizedTestsByFailureFirst( testClasses );
            testClasses.clear();
            testClasses.addAll( prioritized );

        }
        else if ( RunOrder.BALANCED.equals( runOrder ) )
        {
            RunEntryStatisticsMap stat = RunEntryStatisticsMap.fromFile( runOrderParameters.getRunStatisticsFile() );
            List<Class<?>> prioritized = stat.getPrioritizedTestsClassRunTime( testClasses, threadCount );
            testClasses.clear();
            testClasses.addAll( prioritized );

        }
        else if ( sortOrder != null )
        {
            Collections.sort( testClasses, sortOrder );
        }
    }

    private List<Class<?>> sortClassesBySpecifiedOrder( List<Class<?>> testClasses, String flakyTestOrder )
    {
        HashMap<String, Class<?>> classes = new HashMap<>();
        for ( Class<?> each : testClasses )
        {
            classes.put( each.getName(), each );
        }
        LinkedList<Class<?>> ret = new LinkedList<>();
        for ( String s : flakyTestOrder.split( "," ) )
        {
            String testClass = s.substring( s.indexOf( '(' ) + 1, s.length() - 1 );
            Class<?> c = classes.remove( testClass );
            if ( c != null )
            {
                ret.add( c );
            }
        }
        return ret;
    }

    private Comparator<Class> getSortOrderComparator( RunOrder runOrder )
    {
        if ( RunOrder.ALPHABETICAL.equals( runOrder ) )
        {
            return getAlphabeticalComparator();
        }
        else if ( RunOrder.REVERSE_ALPHABETICAL.equals( runOrder ) )
        {
            return getReverseAlphabeticalComparator();
        }
        else if ( RunOrder.HOURLY.equals( runOrder ) )
        {
            final int hour = Calendar.getInstance().get( Calendar.HOUR_OF_DAY );
            return ( ( hour % 2 ) == 0 ) ? getAlphabeticalComparator() : getReverseAlphabeticalComparator();
        }
        else
        {
            return null;
        }
    }

    private Comparator<Class> getReverseAlphabeticalComparator()
    {
        return new Comparator<Class>()
        {
            @Override
            public int compare( Class o1, Class o2 )
            {
                return o2.getName().compareTo( o1.getName() );
            }
        };
    }

    private Comparator<Class> getAlphabeticalComparator()
    {
        return new Comparator<Class>()
        {
            @Override
            public int compare( Class o1, Class o2 )
            {
                return o1.getName().compareTo( o2.getName() );
            }
        };
    }
}
