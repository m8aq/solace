/*
 * Copyright (c) 2021 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.solace.loader.plugins.devtools;

import lombok.AccessLevel;
import lombok.Setter;
import net.runelite.client.ui.ClientUI;

import javax.swing.JFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;

public class DevToolsFrame extends JFrame {
    @Setter(AccessLevel.PACKAGE)
    protected DevToolsButton devToolsButton;

    public DevToolsFrame() {
        setIconImages(Arrays.asList(ClientUI.ICON_128, ClientUI.ICON_16));

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
                devToolsButton.setActive(false);
            }
        });
    }

    public void open() {
        setVisible(true);
        toFront();
        repaint();
    }

    public void close() {
        setVisible(false);

        // dispose(), not just hide. A realized JFrame keeps its native peer until disposed, and AWT
        // holds a JNI global reference to that peer - so a merely-hidden frame is a permanent GC root.
        // It also captures an AccessControlContext at construction, whose ProtectionDomain references
        // the classloader that defined it, so one undisposed dev-tools window pins the entire Solace
        // classloader generation for the life of the JVM. Swing recreates the peer on the next
        // setVisible(true), so re-opening still works.
        dispose();
    }
}
