/*
 * Copyright (c) 2002-2016, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package org.jline.keymap;

import java.io.IOError;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import org.jline.reader.EndOfFileException;
import org.jline.utils.ClosedException;
import org.jline.utils.NonBlockingReader;

/**
 * The BindingReader will transform incoming chars into
 * key bindings
 *
 * @author <a href="mailto:gnodet@gmail.com">Guillaume Nodet</a>
 */
public class BindingReader {

    protected final NonBlockingReader reader;
    protected final StringBuilder opBuffer = new StringBuilder();
    protected final Deque<Integer> pushBackChar = new ArrayDeque<>();
    protected String lastBinding;

    public BindingReader(NonBlockingReader reader) {
        this.reader = reader;
    }

    /**
     * Read from the input stream and decode an operation from the key map.
     *
     * The input stream will be read character by character until a matching
     * binding can be found.  Characters that can't possibly be matched to
     * any binding will be send with the {@link KeyMap#getNomatch()} binding.
     * Unicode (&gt;= 128) characters will be matched to {@link KeyMap#getUnicode()}.
     * If the current key sequence is ambiguous, i.e. the sequence is bound but
     * it's also a prefix to other sequences, then the {@link KeyMap#getAmbiguousTimeout()}
     * timeout will be used to wait for another incoming character.
     * If a character comes, the disambiguation will be done.  If the timeout elapses
     * and no character came in, or if the timeout is &lt;= 0, the current bound operation
     * will be returned.
     *
     * @param keys the KeyMap to use for decoding the input stream
     * @return the decoded binding or <code>null</code> if the end of
     *         stream has been reached
     */
    public <T> T readBinding(KeyMap<T> keys) {
        return readBinding(keys, null, true);
    }

    public <T> T readBinding(KeyMap<T> keys, KeyMap<T> local) {
        return readBinding(keys, local, true);
    }

    public <T> T readBinding(KeyMap<T> keys, KeyMap<T> local, boolean block) {
        lastBinding = null;
        T o = null;
        int[] remaining = new int[1];
        boolean hasRead = false;
        for (;;) {
            if (local != null) {
                o = local.getBound(opBuffer, remaining);
            }
            if (o == null && (local == null || remaining[0] >= 0)) {
                o = keys.getBound(opBuffer, remaining);
            }
            // We have a binding and additional chars
            if (o != null) {
                if (remaining[0] >= 0) {
                    runMacro(opBuffer.substring(opBuffer.length() - remaining[0]));
                    opBuffer.setLength(opBuffer.length() - remaining[0]);
                }
                else {
                    long ambiguousTimeout = keys.getAmbiguousTimeout();
                    if (ambiguousTimeout > 0 && peekCharacter(ambiguousTimeout) != NonBlockingReader.READ_EXPIRED) {
                        o = null;
                    }
                }
                if (o != null) {
                    lastBinding = opBuffer.toString();
                    opBuffer.setLength(0);
                    return o;
                }
                // We don't match anything
            } else if (remaining[0] > 0) {
                int cp = opBuffer.codePointAt(0);
                String rem = opBuffer.substring(Character.charCount(cp));
                lastBinding = opBuffer.substring(0, Character.charCount(cp));
                // Unicode character
                o = (cp >= KeyMap.KEYMAP_LENGTH) ? keys.getUnicode() : keys.getNomatch();
                opBuffer.setLength(0);
                opBuffer.append(rem);
                if (o != null) {
                    return o;
                }
            }

            if (!block && hasRead) {
                break;
            }
            int c = readCharacter();
            if (c == -1) {
                return null;
            }
            opBuffer.appendCodePoint(c);
            hasRead = true;
        }
        return null;
    }

    /**
     * Read a codepoint from the terminal.
     *
     * @return the character, or -1 if an EOF is received.
     */
    public int readCharacter() {
        if (!pushBackChar.isEmpty()) {
            return pushBackChar.pop();
        }
        try {
            int c = NonBlockingReader.READ_EXPIRED;
            int s = 0;
            while (c == NonBlockingReader.READ_EXPIRED) {
                c = reader.read(100L);
                if (c >= 0 && Character.isHighSurrogate((char) c)) {
                    s = c;
                    c = NonBlockingReader.READ_EXPIRED;
                }
            }
            return s != 0 ? Character.toCodePoint((char) s, (char) c) : c;
        } catch (ClosedException e) {
            throw new EndOfFileException(e);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public int peekCharacter(long timeout) {
        if (!pushBackChar.isEmpty()) {
            return pushBackChar.peek();
        }
        try {
            return reader.peek(timeout);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public void runMacro(String macro) {
        macro.codePoints().forEachOrdered(pushBackChar::addLast);
    }

    public String getCurrentBuffer() {
        return opBuffer.toString();
    }

    public String getLastBinding() {
        return lastBinding;
    }

}
