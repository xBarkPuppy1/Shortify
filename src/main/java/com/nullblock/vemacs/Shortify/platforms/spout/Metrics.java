/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

// Modifications by tuxed:
// * Moved the core interface to MetricsImpl
// * 

package com.nullblock.vemacs.Shortify.platforms.spout;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.spout.api.Spout;
import org.spout.api.Server;
import org.spout.api.exception.ConfigurationException;
import org.spout.api.plugin.Platform;
import org.spout.api.plugin.Plugin;
import org.spout.api.plugin.PluginDescriptionFile;
import org.spout.api.scheduler.Task;
import org.spout.api.scheduler.TaskPriority;
import org.spout.api.util.config.yaml.YamlConfiguration;

import com.nullblock.vemacs.Shortify.util.MetricsImpl;

/**
 * <p> The metrics class obtains data about a plugin and submits statistics about it to the metrics backend. </p> <p>
 * Public methods provided by this class: </p>
 * <code>
 * Graph createGraph(String name); <br/>
 * void addCustomData(Plotter plotter); <br/>
 * void start(); <br/>
 * </code>
 */
public class Metrics extends MetricsImpl {

    /**
     * The current revision number
     */
    private final static int REVISION = 6;
    /**
     * The base url of the metrics domain
     */
    private static final String BASE_URL = "http://mcstats.org";
    /**
     * The url used to report a server's status
     */
    private static final String REPORT_URL = "/report/%s";
    /**
     * The separator to use for custom data. This MUST NOT change unless you are hosting your own version of metrics and
     * want to change it.
     */
    private static final String CUSTOM_DATA_SEPARATOR = "~~";
    /**
     * Interval of time to ping (in minutes)
     */
    private static final int PING_INTERVAL = 10;
    /**
     * Debug mode
     */
    private final boolean debug;
    /**
     * The plugin this metrics submits for
     */
    private final Plugin plugin;
    /**
     * All of the custom graphs to submit to metrics
     */
    private final Set<Graph> graphs = Collections.synchronizedSet(new HashSet<Graph>());
    /**
     * The default graph, used for addCustomData when you don't want a specific graph
     */
    private final Graph defaultGraph = new Graph("Default");
    /**
     * The plugin configuration file
     */
    private final YamlConfiguration configuration;
    /**
     * The plugin configuration file
     */
    private final File configurationFile;
    /**
     * Unique server id
     */
    private final String guid;
    /**
     * Lock for synchronization
     */
    private final Object optOutLock = new Object();
    /**
     * Id of the scheduled task
     */
    private volatile Task taskId = null;

    public Metrics(final Plugin plugin) throws ConfigurationException {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        this.plugin = plugin;

        configurationFile = getConfigFile();
        configuration = new YamlConfiguration(configurationFile);
        configuration.load();

        // add some defaults
        configuration.getChild("opt-out").getBoolean(false);
        configuration.getChild("guid").getString(UUID.randomUUID().toString());
        configuration.getChild("debug").getBoolean(false);
        configuration.setHeader("http://metrics.griefcraft.com");
        configuration.save();

        // Load the guid then
        guid = configuration.getNode("guid").getString();
        debug = configuration.getChild("debug").getBoolean(false);
    }

    /**
     * Construct and create a Graph that can be used to separate specific plotters to their own graphs on the metrics
     * website. Plotters can be added to the graph object returned.
     *
     * @param name The name of the graph
     * @return Graph object created. Will never return NULL under normal circumstances unless bad parameters are given
     */
    public Graph createGraph(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Graph name cannot be null");
        }

        // Construct the graph object
        final Graph graph = new Graph(name);

        // Now we can add our graph
        graphs.add(graph);

        // and return back
        return graph;
    }

    /**
     * Add a Graph object to SpoutMetrics that represents data for the plugin that should be sent to the backend
     *
     * @param graph The name of the graph
     */
    public void addGraph(final Graph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        graphs.add(graph);
    }

    /**
     * Adds a custom data plotter to the default graph
     *
     * @param plotter The plotter to use to plot custom data
     */
    public void addCustomData(final Plotter plotter) {
        if (plotter == null) {
            throw new IllegalArgumentException("Plotter cannot be null");
        }

        // Add the plotter to the graph o/
        defaultGraph.addPlotter(plotter);

        // Ensure the default graph is included in the submitted graphs
        graphs.add(defaultGraph);
    }

    /**
     * Start measuring statistics. This will immediately create an async repeating task as the plugin and send the
     * initial data to the metrics backend, and then after that it will post in increments of PING_INTERVAL * 1200
     * ticks.
     *
     * @return True if statistics measuring is running, otherwise false.
     */
    public boolean start() {
        synchronized (optOutLock) {
            // Did we opt out?
            if (isOptOut()) {
                return false;
            }

            // Is metrics already running?
            if (taskId != null) {
                return true;
            }

            // Begin hitting the server with glorious data
            taskId = plugin.getEngine().getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {

                private boolean firstPost = true;

                public void run() {
                    try {
                        // This has to be synchronized or it can collide with the disable method.
                        synchronized (optOutLock) {
                            // Disable Task, if it is running and the server owner decided to opt-out
                            if (isOptOut() && taskId != null) {
                                plugin.getEngine().getScheduler().cancelTask(taskId);
                                taskId = null;
                                // Tell all plotters to stop gathering information.
                                for (Graph graph : graphs) {
                                    graph.onOptOut();
                                }
                            }
                        }

                        // We use the inverse of firstPost because if it is the first time we are posting,
                        // it is not a interval ping, so it evaluates to FALSE
                        // Each time thereafter it will evaluate to TRUE, i.e PING!
                        postPlugin(!firstPost);

                        // After the first post we set firstPost to false
                        // Each post thereafter will be a ping
                        firstPost = false;
                    } catch (IOException e) {
                        if (debug) {
                            Spout.getLogger().log(Level.INFO, "[Metrics] " + e.getMessage());
                        }
                    }
                }
            }, 0, PING_INTERVAL * 1200, TaskPriority.LOW);

            return true;
        }
    }

    /**
     * Has the server owner denied plugin metrics?
     *
     * @return true if metrics should be opted out of it
     */
    public boolean isOptOut() {
        synchronized (optOutLock) {
            try {
                // Reload the metrics file
                configuration.load();
            } catch (ConfigurationException ex) {
                if (debug) {
                    Spout.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                }
                return true;
            }
            return configuration.getNode("opt-out").getBoolean(false);
        }
    }

    /**
     * Enables metrics for the server by setting "opt-out" to false in the config file and starting the metrics task.
     */
    public void enable() {
        // This has to be synchronized or it can collide with the check in the task.
        synchronized (optOutLock) {
            // Check if the server owner has already set opt-out, if not, set it.
            if (isOptOut()) {
                configuration.getNode("opt-out").setValue(false);
                try {
					configuration.save();
				} catch (ConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }

            // Enable Task, if it is not running
            if (taskId == null) {
                start();
            }
        }
    }

    /**
     * Disables metrics for the server by setting "opt-out" to true in the config file and canceling the metrics task.
     *
     * @throws org.spout.api.exception.ConfigurationException
     */
    public void disable() {
        // This has to be synchronized or it can collide with the check in the task.
        synchronized (optOutLock) {
            // Check if the server owner has already set opt-out, if not, set it.
            if (!isOptOut()) {
                configuration.getNode("opt-out").setValue(true);
                try {
					configuration.save();
				} catch (ConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }

            // Disable Task, if it is running
            if (taskId != null) {
                this.plugin.getEngine().getScheduler().cancelTask(taskId);
                taskId = null;
            }
        }
    }

    /**
     * Gets the File object of the config file that should be used to store data such as the GUID and opt-out status
     *
     * @return the File object for the config file
     */
    public File getConfigFile() {
        // I believe the easiest way to get the base folder (e.g craftbukkit set via -P) for plugins to use
        // is to abuse the plugin object we already have
        // plugin.getDataFolder() => base/plugins/PluginA/
        // pluginsFolder => base/plugins/
        // The base is not necessarily relative to the startup directory.
        File pluginsFolder = plugin.getDataFolder().getParentFile();

        // return => base/plugins/PluginMetrics/config.yml
        return new File(new File(pluginsFolder, "PluginMetrics"), "config.yml");
    }

    /**
     * Generic method that posts a plugin to the metrics website
     */
    private void postPlugin(final boolean isPing) throws IOException {
        // Server software specific section
        PluginDescriptionFile description = plugin.getDescription();
        String pluginName = description.getName();
        String pluginVersion = description.getVersion();
        String serverVersion = "Spout " + Spout.getEngine().getVersion();
        int playersOnline;
        if (Spout.getPlatform() == Platform.SERVER) {
            playersOnline = ((Server) Spout.getEngine()).getOnlinePlayers().length;
        } else {
            playersOnline = 1;
        }

        // END server software specific section -- all code below does not use any code outside of this class / Java

        // Construct the post data	
        final StringBuilder data = new StringBuilder();
        // The plugin's description file containg all of the plugin data such as name, version, author, etc
        data.append(encode("guid")).append('=').append(encode(guid));
        encodeDataPair(data, "version", pluginVersion);
        encodeDataPair(data, "server", serverVersion);
        encodeDataPair(data, "players", Integer.toString(playersOnline));
        encodeDataPair(data, "revision", String.valueOf(REVISION));

        // New data as of R6
        String osname = System.getProperty("os.name");
        String osarch = System.getProperty("os.arch");
        String osversion = System.getProperty("os.version");
        String java_version = System.getProperty("java.version");
        int coreCount = Runtime.getRuntime().availableProcessors();

        // normalize os arch .. amd64 -> x86_64
        if (osarch.equals("amd64")) {
            osarch = "x86_64";
        }

        encodeDataPair(data, "osname", osname);
        encodeDataPair(data, "osarch", osarch);
        encodeDataPair(data, "osversion", osversion);
        encodeDataPair(data, "cores", Integer.toString(coreCount));
        encodeDataPair(data, "java_version", java_version);

        // If we're pinging, append it
        if (isPing) {
            encodeDataPair(data, "ping", "true");
        }

        // Acquire a lock on the graphs, which lets us make the assumption we also lock everything
        // inside of the graph (e.g plotters)
        synchronized (graphs) {
            final Iterator<Graph> iter = graphs.iterator();

            while (iter.hasNext()) {
                final Graph graph = iter.next();

                for (Plotter plotter : graph.getPlotters()) {
                    // The key name to send to the metrics server
                    // The format is C-GRAPHNAME-PLOTTERNAME where separator - is defined at the top
                    // Legacy (R4) submitters use the format Custom%s, or CustomPLOTTERNAME
                    final String key = String.format("C%s%s%s%s", CUSTOM_DATA_SEPARATOR, graph.getName(), CUSTOM_DATA_SEPARATOR, plotter.getColumnName());

                    // The value to send, which for the foreseeable future is just the string
                    // value of plotter.getValue()
                    final String value = Integer.toString(plotter.getValue());

                    // Add it to the http post data :)
                    encodeDataPair(data, key, value);
                }
            }
        }

        // Create the url
        URL url = new URL(BASE_URL + String.format(REPORT_URL, encode(pluginName)));

        // Connect to the website
        URLConnection connection;

        // Mineshafter creates a socks proxy, so we can safely bypass it
        // It does not reroute POST requests so we need to go around it
        if (isMineshafterPresent()) {
            connection = url.openConnection(Proxy.NO_PROXY);
        } else {
            connection = url.openConnection();
        }

        connection.setDoOutput(true);

        // Write the data
        final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
        writer.write(data.toString());
        writer.flush();

        // Now read the response
        final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        final String response = reader.readLine();

        // close resources
        writer.close();
        reader.close();

        if (response == null || response.startsWith("ERR")) {
            throw new IOException(response); //Throw the exception
        } else {
            // Is this the first update this hour?
            if (response.contains("OK This is your first update this hour")) {
                synchronized (graphs) {
                    final Iterator<Graph> iter = graphs.iterator();

                    while (iter.hasNext()) {
                        final Graph graph = iter.next();

                        for (Plotter plotter : graph.getPlotters()) {
                            plotter.reset();
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if mineshafter is present. If it is, we need to bypass it to send POST requests
     *
     * @return true if mineshafter is installed on the server
     */
    private boolean isMineshafterPresent() {
        try {
            Class.forName("mineshafter.MineServer");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * <p>Encode a key/value data pair to be used in a HTTP post request. This INCLUDES a & so the first key/value pair
     * MUST be included manually, e.g:</p>
     * <code>
     * StringBuffer data = new StringBuffer();
     * data.append(encode("guid")).append('=').append(encode(guid));
     * encodeDataPair(data, "version", description.getVersion());
     * </code>
     *
     * @param buffer the stringbuilder to append the data pair onto
     * @param key the key value
     * @param value the value
     */
    private static void encodeDataPair(final StringBuilder buffer, final String key, final String value) throws UnsupportedEncodingException {
        buffer.append('&').append(encode(key)).append('=').append(encode(value));
    }

    /**
     * Encode text as UTF-8
     *
     * @param text the text to encode
     * @return the encoded text, as UTF-8
     */
    private static String encode(final String text) throws UnsupportedEncodingException {
        return URLEncoder.encode(text, "UTF-8");
    }
}