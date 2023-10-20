/*
 * Copyright (c) 2016, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.compiler.graal.truffle.nodes.frame;

import static jdk.compiler.graal.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.compiler.graal.nodeinfo.NodeSize.SIZE_0;

import jdk.compiler.graal.core.common.type.StampFactory;
import jdk.compiler.graal.graph.NodeClass;
import jdk.compiler.graal.nodeinfo.NodeInfo;
import jdk.compiler.graal.nodes.ValueNode;
import jdk.compiler.graal.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import jdk.compiler.graal.nodes.spi.Virtualizable;
import jdk.compiler.graal.nodes.spi.VirtualizerTool;
import jdk.compiler.graal.nodes.virtual.VirtualObjectNode;

@NodeInfo(cycles = CYCLES_0, size = SIZE_0)
public final class VirtualFrameCopyNode extends VirtualFrameAccessorNode implements Virtualizable {
    public static final NodeClass<VirtualFrameCopyNode> TYPE = NodeClass.create(VirtualFrameCopyNode.class);

    private final int targetSlotIndex;

    public VirtualFrameCopyNode(Receiver frame, int frameSlotIndex, int targetSlotIndex, VirtualFrameAccessType type, VirtualFrameAccessFlags accessFlags) {
        super(TYPE, StampFactory.forVoid(), frame, frameSlotIndex, -1, type, accessFlags);
        this.targetSlotIndex = targetSlotIndex;
        assert accessFlags.updatesFrame();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode tagAlias = tool.getAlias(frame.getTagArray(type));
        ValueNode objectAlias = tool.getAlias(frame.getObjectArray(type));
        ValueNode primitiveAlias = tool.getAlias(frame.getPrimitiveArray(type));

        if (tagAlias instanceof VirtualObjectNode && objectAlias instanceof VirtualObjectNode && primitiveAlias instanceof VirtualObjectNode) {
            VirtualObjectNode tagVirtual = (VirtualObjectNode) tagAlias;
            VirtualObjectNode objectVirtual = (VirtualObjectNode) objectAlias;
            VirtualObjectNode primitiveVirtual = (VirtualObjectNode) primitiveAlias;

            if (frameSlotIndex < tagVirtual.entryCount() && frameSlotIndex < objectVirtual.entryCount() && frameSlotIndex < primitiveVirtual.entryCount()) {
                if (targetSlotIndex < tagVirtual.entryCount() && targetSlotIndex < objectVirtual.entryCount() && targetSlotIndex < primitiveVirtual.entryCount()) {
                    tool.setVirtualEntry(tagVirtual, targetSlotIndex, tool.getEntry(tagVirtual, frameSlotIndex));
                    if (accessFlags.isObject()) {
                        tool.setVirtualEntry(objectVirtual, targetSlotIndex, tool.getEntry(objectVirtual, frameSlotIndex));
                    }
                    if (accessFlags.isPrimitive()) {
                        tool.setVirtualEntry(primitiveVirtual, targetSlotIndex, tool.getEntry(primitiveVirtual, frameSlotIndex));
                    }
                    tool.delete();
                    return;
                }
            }
        }

        /*
         * Deoptimization is our only option here. We cannot go back to a UnsafeStoreNode because we
         * do not have a FrameState to use for the memory store.
         */
        insertDeoptimization(tool);
    }

    public int getTargetSlotIndex() {
        return targetSlotIndex;
    }

    @Override
    public <State> void updateVerificationState(VirtualFrameVerificationStateUpdater<State> updater, State state) {
        updater.copy(state, getFrameSlotIndex(), getTargetSlotIndex());
    }
}