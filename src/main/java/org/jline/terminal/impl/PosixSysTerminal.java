/*
 * Copyright (c) 2002-2016, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package org.jline.terminal.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jline.utils.InputStreamReader;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.ShutdownHooks;
import org.jline.utils.ShutdownHooks.Task;
import org.jline.utils.Signals;

public class PosixSysTerminal extends AbstractPosixTerminal {

    protected final InputStream input;
    protected final OutputStream output;
    protected final NonBlockingReader reader;
    protected final PrintWriter writer;
    protected final Map<Signal, Object> nativeHandlers = new HashMap<>();
    protected final Task closer;

    public PosixSysTerminal(String name, String type, Pty pty, String encoding,
                            boolean nativeSignals, SignalHandler signalHandler) throws IOException {
        super(name, type, pty, signalHandler);
        Objects.requireNonNull(encoding);
        this.input = pty.getSlaveInput();
        this.output = pty.getSlaveOutput();
        this.reader = new NonBlockingReader(getName(), new InputStreamReader(input, encoding));
        this.writer = new PrintWriter(new OutputStreamWriter(output, encoding));
        parseInfoCmp();
        if (nativeSignals) {
            if (signalHandler == SignalHandler.SIG_DFL) {
                for (final Signal signal : Signal.values()) {
                    Signals.registerDefault(signal.name());
                }
            } else if (signalHandler == SignalHandler.SIG_IGN) {
                for (final Signal signal : Signal.values()) {
                    Signals.registerIgnore(signal.name());
                }
            } else {
                for (final Signal signal : Signal.values()) {
                    nativeHandlers.put(signal, Signals.register(signal.name(), () -> raise(signal)));
                }
            }
        }
        closer = PosixSysTerminal.this::close;
        ShutdownHooks.add(closer);
    }

    @Override
    public SignalHandler handle(Signal signal, SignalHandler handler) {
        SignalHandler prev = super.handle(signal, handler);
        if (handler == SignalHandler.SIG_DFL) {
            Signals.registerDefault(signal.name());
            nativeHandlers.remove(signal);
        } else if (handler == SignalHandler.SIG_IGN) {
            Signals.registerIgnore(signal.name());
            nativeHandlers.remove(signal);
        } else {
            nativeHandlers.put(signal, Signals.register(signal.name(), () -> raise(signal)));
        }
        return prev;
    }

    @Override
    protected void handleDefaultSignal(Signal signal) {
        Object handler = nativeHandlers.get(signal);
        if (handler != null) {
            Signals.invokeHandler(signal.name(), handler);
        }
    }

    public NonBlockingReader reader() {
        return reader;
    }

    public PrintWriter writer() {
        return writer;
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public void close() throws IOException {
        ShutdownHooks.remove(closer);
        for (Map.Entry<Signal, Object> entry : nativeHandlers.entrySet()) {
            Signals.unregister(entry.getKey().name(), entry.getValue());
        }
        super.close();
    }
}
