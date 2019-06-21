package org.apache.maven.surefire.booter;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal command line parser to support optional arguments passed to the booter. >br>
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
    private final static String OPT_PREFIX = "-";
    private final static String OPT_SPLITTER = ":";
    /**
     * Initial command line arguments string
     */
    private final String[] args;
    /**
     * Map of indexed arguments. Keys are the index.
     */
    private final Map<Integer, String> argsIndexed = new HashMap<>();
    /**
     * Map of optional arguments. Keys are the arg name.
     */
    private final Map<String, String> argsOptional = new HashMap<>();

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
    public String getOptionalArg(String key)
    {
        if (!argsOptional.containsKey( key ))
        {
            return null;
        }
        return argsOptional.get( key );
    }

    /**
     * @param key Index.
     * @return Argument via arg index.
     */
    public String getIndexArg(int key)
    {
        if (!argsIndexed.containsKey( key ))
        {
            return null;
        }
        return argsIndexed.get( key );
    }
}
