package org.nodel.jyhost;

/* 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. 
 */

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.joda.time.DateTime;
import org.nodel.DateTimes;
import org.nodel.Handler;
import org.nodel.SimpleName;
import org.nodel.Strings;
import org.nodel.Threads;
import org.nodel.core.ActionRequestHandler;
import org.nodel.core.BindingState;
import org.nodel.core.NodelClientAction;
import org.nodel.core.NodelClientEvent;
import org.nodel.core.NodelEventHandler;
import org.nodel.core.NodelServerAction;
import org.nodel.core.NodelServerEvent;
import org.nodel.host.BaseDynamicNode;
import org.nodel.host.Binding;
import org.nodel.host.Bindings;
import org.nodel.host.LocalBindings;
import org.nodel.host.LogEntry;
import org.nodel.host.NodeConfig;
import org.nodel.host.NodelActionInfo;
import org.nodel.host.NodelEventInfo;
import org.nodel.host.OperationPendingException;
import org.nodel.host.ParamValues;
import org.nodel.host.ParameterBinding;
import org.nodel.host.ParameterBindings;
import org.nodel.host.RemoteBindingValues;
import org.nodel.host.RemoteBindings;
import org.nodel.io.Files;
import org.nodel.io.Stream;
import org.nodel.reflection.Schema;
import org.nodel.reflection.Param;
import org.nodel.reflection.Serialisation;
import org.nodel.reflection.Service;
import org.nodel.reflection.Value;
import org.nodel.threading.TimerTask;
import org.python.core.PyDictionary;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

/**
 * Represents a Python-enabled Node.
 * 
 * Files will be monitored.
 */
public class PyNode extends BaseDynamicNode {
    
    /**
     * Lazy flag to help (but not enforce) avoid overlapping
     * operations.
     */
    private ReentrantLock _busy = new ReentrantLock();
    
    /**
     * When permanently closed (disposed)
     */
    private boolean _closed;
            
    /**
     * The script file.
     */
    private File _scriptFile;
    
    /**
     * The hash used to determine whether the config and / or script files were modified.
     */
    private long _fileModifiedHash;
    
    /**
     * The current Python interpreter.
     */
    private PythonInterpreter _python;
    
    /**
     * Used when calling functions in a multithreaded Python environment.
     */
    private AtomicLong _funcSeqNumber = new AtomicLong();
    
    /**
     * Stores the time-in by function name.
     */
    private Map<String, Long> _activeFunctions = new HashMap<String, Long>();
            
    /**
     * Create a new pyNode.
     */
    public PyNode(File root) throws IOException {
        super(root);

        init();
    } // (constructor)
    
    public void saveConfig(NodeConfig config) throws Exception {
        if (!_busy.tryLock())
            throw new OperationPendingException();
        
        try {
            saveConfig0(config);

        } finally {
            _busy.unlock();
        }
    } // (method)
    
    /**
     * (internal version)
     */
    private void saveConfig0(NodeConfig config) throws Exception {
        try {
            _logger.info("saveConfig called.");

            if (config == null)
                return;

            this.applyConfig(config);

            String configStr = Serialisation.serialise(config, 4);

            Stream.writeFully(_configFile, configStr);

            // make sure it's not triggered again
            _fileModifiedHash = _configFile.lastModified() + _scriptFile.lastModified();
            
            _logger.info("saveConfig completed.");
        } catch (Exception exc) {
            _logger.warn("saveConfig failed.", exc);
            throw exc;
        }        
    }
    
    public class ScriptInfo {
        
        @Value(name = "modified", title = "Modified", desc = "When the script was last modified.")
        public DateTime modified;
        
        @Value(name = "script", title = "Script", desc = "The script itself.")
        public String script;
        
    } // (method)
    
    /**
     * Used to provide the '/script' end point. 
     */
    public class Script {
        
        @Value(name = "scriptInfo", title = "Script info", desc = "Retrieves the script info including the script itself.", treatAsDefaultValue = true)
        public ScriptInfo getScriptInfo() throws IOException {
            ScriptInfo scriptInfo = new ScriptInfo();
            scriptInfo.modified = new DateTime(_scriptFile.lastModified());
            scriptInfo.script = Stream.readFully(_scriptFile); 
            return scriptInfo;
        } // (method)
        
        @Service(name = "raw", title = "Raw script", desc = "Retrieves the actual .py script itself.", contentType = "text/plain")
        public String getRawScript() throws IOException {
            return Stream.readFully(_scriptFile);
        } // (method)        
        
        @Service(name = "save", title = "Save", desc = "Saves the script.")
        public void save(@Param(name = "script", title = "Script", desc = "The script text.") String script) throws IOException {
            // perform a rolling backup
            
            final String scriptFilePrefix = "script_backup_";
            
            // get the list of 'script (backup *.py files)
            File[] files = _root.listFiles(new FileFilter() {
                
                @Override
                public boolean accept(File pathname) {
                    String lc = pathname.getName().toLowerCase();
                    return lc.startsWith(scriptFilePrefix) && lc.endsWith(".py");
                }
                
            });
            
            // sort by last modified
            Arrays.sort(files, new Comparator<File>() {
                
                @Override
                public int compare(File f1, File f2) {
                    long l1 = f1.lastModified();
                    long l2 = f2.lastModified();
                    return (l1 == l2 ? 0 : (l2 > l1 ? 0 : 1));
                }
                
            });
            
            // drop the oldest one if more than 10 are listed
            if (files.length > 5)
                files[0].delete();
            
            // make the backup file
            String timestamp = DateTime.now().toString("YYYY-MM-dd_HHmmssSSS");
            Files.copy(_scriptFile, new File(_root, scriptFilePrefix + timestamp + ".py"));
                
            Stream.writeFully(_scriptFile, script);
            
            // do not update time stamp here, let the monitoring take care of that
            
        } // (method)
        
    } // (class)
    
    /**
     * The script end-point.
     */
    private Script _script = new Script();
    
    @Service(name = "script", title = "Script", desc = "The .py script itself and meta-data.")
    public Script getScript() {
        return _script;
    }

    /**
     * Once-off initialisation routine.
     */
    private void init() throws IOException {
        String filePrefix = "nodeConfig";
        
        // dump the bootstrap config schema if it hasn't already been dumped
        Object schema = Schema.getSchemaObject(NodeConfig.class);
        String schemaString = Serialisation.serialise(schema, 4);
        
        // load for the bootstrap config schema file    
        File schemaFile = new File(_root, "_" + filePrefix + "_schema.json");
        String schemaFileString = null;
        if(schemaFile.exists()) {
            schemaFileString = Stream.readFully(schemaFile);
        }
        
        // only write out the schema file if it's different or never been written out
        if(schemaFileString == null || !schemaFileString.equals(schemaString)) {
            Stream.writeFully(schemaFile, schemaString);
        }
        
        // dump the example config if it hasn't already been dumped
        String exampleString = Serialisation.serialise(NodeConfig.Example, 4);
        
        // load for the bootstrap config schema file    
        File exampleFile = new File(_root, "_" + filePrefix + "_example.json");
        String exampleFileString = null;
        if(exampleFile.exists()) {
            exampleFileString = Stream.readFully(exampleFile);
        }
        
        // only write out the schema file if it's different or never been written out
        if(exampleFileString == null || !exampleFileString.equals(schemaString)) {
            Stream.writeFully(exampleFile, exampleString);
        }        
        
        _configFile = new File(_root, filePrefix + ".json");
        
        // dump an *empty one* if there's nothing there
        if (!_configFile.exists()) {
            Stream.writeFully(_configFile, Serialisation.serialise(NodeConfig.Empty, 4));
        }
        
        _scriptFile = new File(_root, "script.py");
        if (!_scriptFile.exists())
            Stream.writeFully(_scriptFile, ExampleScript.generateExampleScript());
        
        s_threadPool.execute(new Runnable() {
            
            @Override
            public void run() {
                monitorConfig();
            }
            
        });
        
        // check the active functions every min or so
        if (!_closed) {
            s_timerThread.schedule(new TimerTask() {
                
                @Override
                public void run() {
                    checkActiveFunctions();
                }
                
            }, 60000);
        }
    } // (method)
    
    private void checkActiveFunctions() {
        StringBuilder sb = null;
        try {
            synchronized (_activeFunctions) {
                int count = _activeFunctions.size();
                if (count <= 0)
                    return;

                for (Entry<String, Long> entry : _activeFunctions.entrySet()) {
                    String name = entry.getKey();
                    long timeIn = entry.getValue();
                    
                    // check if it's been stuck for more than 2 minutes
                    long stuckMillis = (System.nanoTime() - timeIn) / 1000000; 
                    if (stuckMillis > 2 * 60000) {
                        if (sb == null)
                            sb = new StringBuilder();
                        else
                            sb.append(", ");
                        
                        sb.append(name + " (" + DateTimes.formatShortDuration(stuckMillis) + " ago)");
                    }
                }
            }

            if (sb != null) {
                String message = "These functions are stuck - " + sb;
                _errReader.inject(message);
                _logger.warn(message);
            }
        } finally {

            if (!_closed) {
                s_timerThread.schedule(new TimerTask() {

                    @Override
                    public void run() {
                        checkActiveFunctions();
                    }

                }, 60000);
            }
        }
    }

    /**
     * Monitors changes to the config or script file and re-launches.
     * I/O is involved so may be blocking.
     */
    private void monitorConfig() {
        try {
            NodeConfig config;
            if (_configFile.exists()) {
                // use most recent 'modified' of the config or script file
                
                // (neither file might exists, but functions safely return '0')
                long lastModifiedHash = _configFile.lastModified() + _scriptFile.lastModified();
                
                if (lastModifiedHash != _fileModifiedHash) {
                    config = (NodeConfig) Serialisation.coerceFromJSON(NodeConfig.class, Stream.readFully(_configFile));
                    applyConfig(config);
                    
                    _fileModifiedHash = lastModifiedHash; 
                    
                    _logger.info("Config updated successfully.");
                }
            }
            
        } catch (Exception exc) {
            _logger.warn("Config monitoring failed; will backoff and retry.", exc);
            exc.printStackTrace();
            
        } finally {
            if (!_closed) {
                s_timerThread.schedule(s_threadPool, new TimerTask() {

                    @Override
                    public void run() {
                        monitorConfig();
                    }

                }, 10000);
            }
        }
    } // (method)
    
    /**
     * Applies the full config (and executes script file)
     */
    private void applyConfig(final NodeConfig config) throws Exception {
        try {
            _busy.lock();

            Threads.AsyncResult<Void> op = Threads.executeAsync(new Callable<Void>() {
                
                @Override
                public Void call() throws Exception {
                    applyConfig0(config);
                    return null;
                }
                
            });

            op.waitForResultOrThrowException();
        } finally {
            _busy.unlock();
        }
    } // (method)
    
    /**
     * This needs to be done from a clean thread (non-pooled, daemon) otherwise Python
     * cannot cleanup after itself waiting for, what becomes, its 'MainThread' thread to die.
     */
    private void applyConfig0(NodeConfig config) throws Exception {
        boolean hasErrors = false;
        
        cleanupInterpreter();
        
        long startTime = System.nanoTime();
        
        _logger.info("Initialising new Python interpreter...");
        
        PySystemState pySystemState = new PySystemState();

        // set the current working directory
        pySystemState.setCurrentWorkingDir(_root.getAbsolutePath());
        
        // append the Node's root directory to the path
        pySystemState.path.append(new PyString(_root.getAbsolutePath()));
        
        PyDictionary locals = new PyDictionary();
        
        _python = new PythonInterpreter(locals, pySystemState);
        
        _logger.info("Interpreter initialised (took {}).", DateTimes.formatPeriod(startTime)); 
        
        // redirect 
        _python.setErr(_errReader);
        _python.setOut(_outReader);       
        
        // apply monkey patching
        try(InputStream moneyPatchStream = PyNode.class.getResourceAsStream("monkeyPatch.py")) {
            _python.execfile(moneyPatchStream);
        }
        
        // dump a new example script if necessary
        String exampleScript = ExampleScript.generateExampleScript();
        File exampleScriptFile = new File(_root, "_script_example.py");
        String exampleStringFileStr = null;
        if (exampleScriptFile.exists())
            exampleStringFileStr = Stream.readFully(exampleScriptFile);
        if (exampleStringFileStr == null || !exampleScript.equals(exampleStringFileStr))
            Stream.writeFully(exampleScriptFile, exampleScript);
        
        Bindings bindings = Bindings.Empty;
        
        try {
            if (!_scriptFile.exists())
                throw new FileNotFoundException("No script file exists.");
            
            _python.execfile(_scriptFile.getAbsolutePath());
            
            List<String> warnings = new ArrayList<String>();
            
            bindings = BindingsExtractor.extract(_python, warnings);

            if (warnings.size() > 0) {
                for (String warning : warnings) {
                    _errReader.inject(warning);
                }
            }
            
            // apply the binding and parameter values
            injectRemoteBindingValues(config, bindings.remote);
            
            injectParamValues(config, bindings.params);
            
            _config = config;
        } catch (Exception exc) {
            hasErrors = true;
            
            // inject into the error
            _errReader.inject(exc.toString());
            
            _logger.warn("The bindings could not be applied to the Python instance.", exc);
        }
        
        applyBindings(bindings);
        
        _bindings = bindings;
        
        try {
            // log a message to the console and the program log
            String msg;
            if (!hasErrors) {
                msg = "Python and Node script loaded (took " + DateTimes.formatPeriod(startTime) + "); calling 'main'...";
                _outReader.inject(msg);
                _logger.info(msg);
            } else {
                msg = "Python and Node script loaded with errors (took " + DateTimes.formatPeriod(startTime) + "); calling 'main'...";
                _errReader.inject(msg);
                _logger.warn(msg);
            }
            
            try {
                
                if (_python.get("main") == null) {
                    msg = "(no 'main' method to call)";
                    
                } else {
                    _python.exec("main()");
                
                    msg = "'main' completed cleanly.";
                }
                
                _logger.info(msg);
                _outReader.inject(msg);

                // config has changed, so update creation time
                synchronized (_signal) {
                    _started = DateTime.now();
                    _desc = _bindings.desc;
                    _signal.notifyAll();
                }                
                
            } catch (Exception exc) {
                // don't let this interrupt anything

                // log will end up in console anyway
                msg = "'main' completed with errors - " + exc; 

                _logger.warn(msg);
                _errReader.inject(msg);
            }
        } finally {
            _config = config;
        }        
    }
    
    /**
     * Cleans up the interpreter.
     */
    private void cleanupInterpreter() {
        if (_python != null) {
            _logger.info("Cleaning up previous interpreter...");
            _outReader.inject("Closing this interpreter...");

            _python.cleanup();
            
            String message = "Clean up complete.";
            _logger.info(message);
            _outReader.inject(message);
        }
    } // (method)
    
    /**
     * (assumes locked)
     */
    private void applyBindings(Bindings bindings) {
        if (bindings == null) {
            _logger.info("No bindings were specified.");
            return;
        }
        
        cleanupBindings();
        
        // deal with the local bindings
        int count = bindLocalBindings(bindings.local);
        
        // check if we need to use a 'dummy' binding
        if (count == 0) {
            // bind 'dummy' so advertisement still takes place
            _dummyBinding = new NodelServerAction(_name.getOriginalName(), "Dummy");
            _dummyBinding.registerAction(new ActionRequestHandler() {

                @Override
                public Object handleActionRequest(Object arg) {
                    // no-op
                    return null;
                }

            });
        }

        // deal with the remote bindings
        bindRemoteBindings(bindings.remote);
        
        // deals with the parameters
        bindParams(bindings.params);
    } // (method)    

    /**
     * (assumes locked)
     * @return The number of bindings
     */
    private int bindLocalBindings(LocalBindings provides) {
        if (provides == null) {
            _logger.info("This node does not provide any events nor actions.");
            return 0;
        }
        
        int count = 0;
        
        // go through the actions
        count += bindLocalActions(provides.actions);
        
        // go through the events
        count += bindLocalEvents(provides.events);
        
        return count;
    }
        
    /**
     * Binds actions.
     */
    private int bindLocalActions(Map<SimpleName, Binding> actions) {
        if (actions == null)
            return 0;
        
        StringBuilder sb = new StringBuilder();

        for (final Entry<SimpleName, Binding> entry : actions.entrySet()) {
            // (Nodel layer)
            NodelServerAction serverAction = new NodelServerAction(_name.getOriginalName(), entry.getKey().getReducedName());
            serverAction.registerAction(new ActionRequestHandler() {
                
                @Override
                public Object handleActionRequest(Object arg) {
                    addLog(DateTime.now(), LogEntry.Source.local, LogEntry.Type.action, entry.getKey().getReducedName(), arg);
                    return PyNode.this.handleActionRequest(entry.getKey().getReducedName(), arg);
                }
                
            });
            
            _localActions.put(entry.getKey(), new ServerActionEntry(entry.getValue(), serverAction));
            
            if (sb.length() > 0)
                sb.append(", ");
            
            sb.append("local_action_" + entry.getKey().getReducedName());
        }

        if (sb.length() > 0)
            _logger.info("Mapped this Node's actions to Python functions {}", sb);
        else
            _logger.info("This Node has no actions.");

        return actions.size();
    } // (method)
    
    /**
     * When an action request arrives via Nodel layer.
     * @throws Exception 
     */
    protected Object handleActionRequest(String action, Object arg) {
        _logger.info("Action requested - {}", action);
        
        // is a threaded environment so need sequence numbering
        long num = _funcSeqNumber.getAndIncrement();
        
        String functionName = "local_action_" + action;
        
        String functionKey = functionName + "_" + num;
        
        String functionArgName = functionName + "_arg_" + num;
        
        try {
            synchronized (_activeFunctions) {
                _activeFunctions.put(functionKey, System.nanoTime());
            }

            // create temporary argument
            _python.set(functionArgName, arg);

            // evaluate the function
            PyObject pyObject = _python.eval(functionName + "(" + functionArgName + ")");
            
            return pyObject;
            
        } catch (Exception exc) {
            String message = "Action call failed - " + exc;
            _logger.info(message);
            _errReader.inject(message);
            
            throw new RuntimeException(exc);
        } finally {
            // clean up the active function map and temporary argument
            synchronized(_activeFunctions) {
                _activeFunctions.remove(functionKey);
            }
            
            //   ( .set(...) uses .getLocals().__setitem__
            try {
                _python.getLocals().__delitem__(functionArgName.intern());
            } catch (Exception ignore) {
            }
        }
    } // (method)
        
    /**
     * Binds the 'provided events'.
     */
    private int bindLocalEvents(Map<SimpleName, Binding> events) {
        if (events == null)
            return 0;
        
        StringBuilder sb = new StringBuilder();

        for(final Entry<SimpleName, Binding> eventBinding : events.entrySet()) {
            // (Nodel layer and Python)
            NodelServerEvent nodelServerEvent = new NodelServerEvent(_name.getOriginalName(), eventBinding.getKey().getReducedName());
            nodelServerEvent.attachMonitor(new Handler.H1<Object>() {
                @Override
                public void handle(Object arg) {
                    addLog(DateTime.now(), LogEntry.Source.local, LogEntry.Type.event, eventBinding.getKey().getReducedName(), arg);
                }
            });
            nodelServerEvent.registerEvent();
            
            String varName = "local_event_" + eventBinding.getKey().getReducedName();
            _python.set(varName, nodelServerEvent);
            
            _localEvents.put(eventBinding.getKey(), new ServerEventEntry(eventBinding.getValue(), nodelServerEvent));
            
            if (sb.length() > 0)
                sb.append(", ");
            
            sb.append(varName);            
        } // (for)
        
        if (sb.length() > 0)
            _logger.info("Mapped this Node's events to Python variables {}", sb);
        else
            _logger.info("This Node has no events.");
        
        return events.size();
    } // (method)
    
    private void bindRemoteBindings(RemoteBindings remoteBindings) {
        if (remoteBindings == null) {
            _logger.info("This node does not require any events nor actions.");
            return;
        }
        
        bindRemoteActions(remoteBindings.actions);
        
        bindRemoteEvents(remoteBindings.events);
    } // (method)
    
    public class Remote {

        /**
         * The parameters bindings.
         */
        @Service(name = "schema", title = "Schema", desc = "Returns the processed schema that produces data that can be used by 'save'.")
        public Map<String, Object> getSchema() {
            return _bindings.remote.asSchema();
        }
        
        @Service(name = "save", title = "Save", desc = "Saves the remote binding values.")
        public void save(@Param(name = "value", desc = "The remoting binding values.", isMajor = true, title = "Value") 
                         RemoteBindingValues remoteBindingValues) throws Exception {
            if (!_busy.tryLock())
                throw new OperationPendingException();
            
            try {
                _config.remoteBindingValues = remoteBindingValues;
                injectRemoteBindingValues(_config, _bindings.remote);

                saveConfig0(_config);
            } finally {
                _busy.unlock();
            }
        }
        
        @Value(name = "value", title = "Value", desc = "The remote binding values.", treatAsDefaultValue = true)
        public RemoteBindingValues getValue() {
            return _config.remoteBindingValues;
        }        

    } // (class)
    
    /**
     * Holds the live params.
     */
    private Remote _remote = new Remote();

    @Service(name = "remote", title = "Remote", desc = "The remote bindings.")
    public Remote getRemote() {
        return _remote;
    }    
        
    private List<RemoteActionEntry> _clientActions = new ArrayList<RemoteActionEntry>();
    
    private void bindRemoteActions(Map<SimpleName, NodelActionInfo> actions) {
        if (actions == null) {
            _logger.info("This node does not require any actions.");
            return;
        }
        
        for(final Entry<SimpleName, NodelActionInfo> action : actions.entrySet()) {
            NodelActionInfo actionInfo = action.getValue();
            
            String varName = "remote_action_" + action.getKey().getReducedName();
            
            String nodeName = actionInfo.node;
            String actionName = actionInfo.action;
            
            // empty bindings are allowed and will show up as "unbound" sources
            
            // (Nodel layer)
            final NodelClientAction nodelAction = new NodelClientAction(nodeName, actionName);
            nodelAction.attachMonitor(new Handler.H1<Object>() {
                
                @Override
                public void handle(Object arg) {
                    if (nodelAction.isUnbound())
                        addLog(DateTime.now(), LogEntry.Source.unbound, LogEntry.Type.action, action.getKey().getReducedName(), arg);                    
                    else
                        addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.action, action.getKey().getReducedName(), arg);
                }
                
            });
            nodelAction.attachWiredStatusChanged(new Handler.H1<BindingState>() {
                
                @Override
                public void handle(BindingState status) {
                    _logger.info("Action binding status: {} - '{}'", action.getKey().getReducedName(), status);
                    
                    addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.actionBinding, action.getKey().getReducedName(), status);
                }
                
            });
            nodelAction.registerActionInterest();
            
            // (Python)
            _python.set(varName, nodelAction);

            _clientActions.add(new RemoteActionEntry(nodelAction));
            
            _logger.info("Mapped peer action to Python variable '{}'", varName);
        } // (for)
        
    } // (method)
        
    private List<RemoteEventEntry> _remoteEvents = new ArrayList<RemoteEventEntry>();
    
    private void bindRemoteEvents(Map<SimpleName, NodelEventInfo> events) {
        if (events == null) {
            _logger.info("This node does not require any events.");
            return;
        }
        
        // for summary in log
        StringBuilder sb = new StringBuilder();

        for (Entry<SimpleName, NodelEventInfo> entry : events.entrySet()) {
            final SimpleName alias = entry.getKey();
            NodelEventInfo eventInfo = entry.getValue();
            
            final String pythonEvent = "remote_event_" + alias;

            String nodeName = eventInfo.node;
            String eventName = eventInfo.event;
            
            if (Strings.isNullOrEmpty(nodeName) || Strings.isNullOrEmpty(eventName))
                // skip for now
                continue;

            final NodelClientEvent nodelClientEvent = new NodelClientEvent(nodeName, eventName);
            nodelClientEvent.setHandler(new NodelEventHandler() {
                
                @Override
                public void handleEvent(SimpleName node, SimpleName event, Object arg) {
                    handleEventArrival(alias, nodelClientEvent, pythonEvent, arg);
                }
                
            });
            
            nodelClientEvent.attachWiredStatusChanged(new Handler.H1<BindingState>() {
                
                @Override
                public void handle(BindingState status) {
                    addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.eventBinding, alias.getReducedName(), status);
                    
                    _logger.info("Event binding status: {} - '{}'", alias.getReducedName(), status);
                }
                
            });            
            
            _remoteEvents.add(new RemoteEventEntry(nodelClientEvent));
            
            if (sb.length() > 0)
                sb.append(", ");

            sb.append('"').append(pythonEvent).append('"');
        } // (for)
        
        if (sb.length() > 0)
            _logger.info("Mapped peer events to Python events {}", sb.toString());

    } // (method)
    
    /**
     * When an action request arrives via Nodel layer.
     * @param alias 
     * @param nodelClientEvent 
     */
    private void handleEventArrival(SimpleName alias, NodelClientEvent nodelClientEvent, String functionName, Object arg) {
        _logger.info("Event arrived - {}", nodelClientEvent.getNodelPoint());
        
        addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.event, alias.getReducedName(), arg);
        
        // is a threaded environment so need sequence numbering
        long num = _funcSeqNumber.getAndIncrement();
        
        String functionKey = functionName + "_" + num;

        // create temporary argument
        String functionArgName = functionName + "_arg_" + num;
        
        synchronized (_activeFunctions) {
            _activeFunctions.put(functionKey, System.nanoTime());
        }

        try {
            _python.set(functionArgName, arg);

            // evaluate the function
            _python.exec(functionName + "(" + functionArgName + ")");

        } catch (Exception exc) {
            _logger.info("Script threw an exception while handling an event '" + alias + "'", exc);

            _errReader.inject("Exception occurred while handling an event '" + functionName + "' - " + exc);
        } finally {
            // clean up the and active function map temporary argument
            synchronized (_activeFunctions) {
                _activeFunctions.remove(functionKey);
            }

            // ( .set(...) uses .getLocals().__setitem__
            try {
                _python.getLocals().__delitem__(functionArgName.intern());
            } catch (Exception ignore) {
            }
        }

    } // (method)
    
    /**
     * Binds the parameters to the script.
     * @param params
     */
    private void bindParams(ParameterBindings params) {
        if (params == null) {
            _logger.info("This node does use any parameters.");
            return;
        }

        for (Entry<SimpleName, ParameterBinding> entry : params.entrySet()) {
            SimpleName name = entry.getKey();
            ParameterBinding paramBinding = entry.getValue();
            
            Object value = paramBinding.value;
            
            String paramName = "param_" + name.getReducedName();
            
            _python.set(paramName, value);
            
            _parameters.add(new ParameterEntry(name));

            _logger.info("Created parameter '{}' in script (initial value '{}').", paramName, value);
        } // (for)

    } // (method)
    
    public class Params {

        /**
         * The parameters bindings.
         */
        @Service(name = "schema", title = "Schema", desc = "Returns the processed schema that produced data that can be used by 'save'.")
        public Map<String, Object> getSchema() {
            return _bindings.params.asSchema();
        } // (method)
        
        @Service(name = "save", title = "Save", desc = "Saves a set of parameters.")
        public void save(@Param(name = "value", title = "Value", desc = "The parameter values.", isMajor = true, genericClassA = SimpleName.class, genericClassB = Object.class) 
                         ParamValues paramValues) throws Exception {
            if (!_busy.tryLock())
                throw new OperationPendingException();
            
            try {
                _config.paramValues = paramValues;
                injectParamValues(_config, _bindings.params);

                saveConfig0(_config);
            } finally {
                _busy.unlock();
            }
        } // (method)
        
        @Value(name = "value", title = "Value", desc = "The value object.", treatAsDefaultValue = true)
        public ParamValues getValue() {
            return _config.paramValues;
        }

    } // (class)
    
    /**
     * Holds the live params.
     */
    private Params _params = new Params();

    @Service(name = "params", title = "Params", desc = "The live parameters.")
    public Params getParams() {
        return _params;
    }
    
    /**
     * Evaluates a Python expression related to the current interpreter instance.
     */
    @Service(name = "eval", title = "Evaluate", desc = "Evaluates a Python expression.")
    public Object eval(@Param(name = "expr", title = "Expression", desc = "A Python expression.") final String expr) throws Exception {
        final PythonInterpreter python = _python;
        
        if (python == null)
            throw new RuntimeException("The interpreter has not been initialised yet.");
        
        Threads.AsyncResult<Object> op = Threads.executeAsync(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return python.eval(expr);
            }
        });
        
        return op.waitForResultOrThrowException();
    } // (method)
    
    /**
     * Evaluates a Python expression related to the current interpreter instance.
     */
    @Service(name = "exec", title = "Execute", desc = "Execute Python code fragment.")
    public void exec(@Param(name = "code", title = "Code", desc = "A Python expression.") final String code) throws Exception {
        if (code == null)
            throw new IllegalArgumentException("'code' argument cannot be missing.");
        
        final PythonInterpreter python = _python;
        
        if (python == null)
            throw new RuntimeException("The interpreter has not been initialised yet.");
        
        Threads.AsyncResult<Object> op = Threads.executeAsync(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                python.exec(code);
                
                return null;
            }
        });
        
        op.waitForResultOrThrowException();
    } // (method)
    
    /**
     * Outside callers can inject error messages related to this node.
     */
    protected void notifyOfError(Exception exc) {
        _errReader.inject(exc.toString());
    }
    
    /**
     * Permanently shuts down the node.
     */
    public void close() {
        synchronized (_signal) {
            if (_closed)
                return;
            
            _logger.info("Closing node...");
            
            cleanupBindings();
            
            cleanupInterpreter();
            
            _closed = true;
            
            // stuff
        }
    } // (method)

} // (class)
