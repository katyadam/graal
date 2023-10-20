/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.compiler.graal.hotspot.aarch64;

import static jdk.vm.ci.aarch64.AArch64.lr;
import static jdk.vm.ci.hotspot.aarch64.AArch64HotSpotRegisterConfig.fp;
import static jdk.vm.ci.hotspot.aarch64.AArch64HotSpotRegisterConfig.inlineCacheRegister;
import static jdk.vm.ci.hotspot.aarch64.AArch64HotSpotRegisterConfig.metaspaceMethodRegister;

import jdk.compiler.graal.core.aarch64.AArch64NodeLIRBuilder;
import jdk.compiler.graal.core.aarch64.AArch64NodeMatchRules;
import jdk.compiler.graal.core.common.LIRKind;
import jdk.compiler.graal.core.common.spi.ForeignCallDescriptor;
import jdk.compiler.graal.core.common.spi.ForeignCallLinkage;
import jdk.compiler.graal.core.gen.DebugInfoBuilder;
import jdk.compiler.graal.hotspot.HotSpotBackend;
import jdk.compiler.graal.hotspot.HotSpotDebugInfoBuilder;
import jdk.compiler.graal.hotspot.HotSpotLIRGenerator;
import jdk.compiler.graal.hotspot.HotSpotLockStack;
import jdk.compiler.graal.hotspot.HotSpotNodeLIRBuilder;
import jdk.compiler.graal.hotspot.nodes.HotSpotDirectCallTargetNode;
import jdk.compiler.graal.hotspot.nodes.HotSpotIndirectCallTargetNode;
import jdk.compiler.graal.lir.LIRFrameState;
import jdk.compiler.graal.lir.Variable;
import jdk.compiler.graal.lir.aarch64.AArch64BreakpointOp;
import jdk.compiler.graal.lir.gen.LIRGeneratorTool;
import jdk.compiler.graal.nodes.BreakpointNode;
import jdk.compiler.graal.nodes.CallTargetNode.InvokeKind;
import jdk.compiler.graal.nodes.DirectCallTargetNode;
import jdk.compiler.graal.nodes.FullInfopointNode;
import jdk.compiler.graal.nodes.IndirectCallTargetNode;
import jdk.compiler.graal.nodes.NodeView;
import jdk.compiler.graal.nodes.SafepointNode;
import jdk.compiler.graal.nodes.StructuredGraph;
import jdk.compiler.graal.nodes.ValueNode;
import jdk.compiler.graal.nodes.spi.NodeValueMap;

import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.Value;

/**
 * LIR generator specialized for AArch64 HotSpot.
 */
public class AArch64HotSpotNodeLIRBuilder extends AArch64NodeLIRBuilder implements HotSpotNodeLIRBuilder {

    @SuppressWarnings("this-escape")
    public AArch64HotSpotNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool gen, AArch64NodeMatchRules nodeMatchRules) {
        super(graph, gen, nodeMatchRules);
        assert gen instanceof AArch64HotSpotLIRGenerator;
        assert getDebugInfoBuilder() instanceof HotSpotDebugInfoBuilder;
        ((AArch64HotSpotLIRGenerator) gen).setDebugInfoBuilder(((HotSpotDebugInfoBuilder) getDebugInfoBuilder()));
    }

    @Override
    protected DebugInfoBuilder createDebugInfoBuilder(StructuredGraph graph, NodeValueMap nodeValueMap) {
        HotSpotLockStack lockStack = new HotSpotLockStack(gen.getResult().getFrameMapBuilder(), LIRKind.value(AArch64Kind.QWORD));
        return new HotSpotDebugInfoBuilder(nodeValueMap, lockStack, (HotSpotLIRGenerator) gen);
    }

    private AArch64HotSpotLIRGenerator getGen() {
        return (AArch64HotSpotLIRGenerator) gen;
    }

    @Override
    protected void emitPrologue(StructuredGraph graph) {
        CallingConvention incomingArguments = gen.getResult().getCallingConvention();
        Value[] params = new Value[incomingArguments.getArgumentCount() + 2];

        prologAssignParams(incomingArguments, params);
        params[params.length - 2] = fp.asValue(LIRKind.value(AArch64Kind.QWORD));
        params[params.length - 1] = lr.asValue(LIRKind.value(AArch64Kind.QWORD));

        gen.emitIncomingValues(params);

        prologSetParameterNodes(graph, params);
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        LIRFrameState info = state(i);
        Register thread = getGen().getProviders().getRegisters().getThreadRegister();
        Variable scratch = gen.newVariable(LIRKind.value(getGen().target().arch.getWordKind()));
        append(new AArch64HotSpotSafepointOp(info, getGen().config, thread, scratch));
    }

    @Override
    protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        InvokeKind invokeKind = ((HotSpotDirectCallTargetNode) callTarget).invokeKind();
        if (invokeKind.isIndirect()) {
            append(new AArch64HotSpotDirectVirtualCallOp(callTarget.targetMethod(), result, parameters, temps, callState, invokeKind, getGen().config));
        } else {
            assert invokeKind.isDirect();
            HotSpotResolvedJavaMethod resolvedMethod = (HotSpotResolvedJavaMethod) callTarget.targetMethod();
            assert resolvedMethod.isConcrete() : "Cannot make direct call to abstract method.";
            append(new AArch64HotSpotDirectStaticCallOp(callTarget.targetMethod(), result, parameters, temps, callState, invokeKind, getGen().config));
        }
    }

    @Override
    protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        Value metaspaceMethodSrc = operand(((HotSpotIndirectCallTargetNode) callTarget).metaspaceMethod());
        Value targetAddressSrc = operand(callTarget.computedAddress());
        AllocatableValue metaspaceMethodDst = metaspaceMethodRegister.asValue(metaspaceMethodSrc.getValueKind());
        AllocatableValue targetAddressDst = inlineCacheRegister.asValue(targetAddressSrc.getValueKind());
        gen.emitMove(metaspaceMethodDst, metaspaceMethodSrc);
        gen.emitMove(targetAddressDst, targetAddressSrc);
        append(new AArch64IndirectCallOp(callTarget.targetMethod(), result, parameters, temps, metaspaceMethodDst, targetAddressDst, callState));
    }

    @Override
    public void emitPatchReturnAddress(ValueNode address) {
        append(new AArch64HotSpotPatchReturnAddressOp(gen.asAllocatable(operand(address))));
    }

    @Override
    public void emitJumpToExceptionHandlerInCaller(ValueNode handlerInCallerPc, ValueNode exception, ValueNode exceptionPc) {
        AllocatableValue handler = gen.asAllocatable(operand(handlerInCallerPc));
        ForeignCallLinkage linkage = gen.getForeignCalls().lookupForeignCall(HotSpotBackend.EXCEPTION_HANDLER_IN_CALLER);
        CallingConvention outgoingCc = linkage.getOutgoingCallingConvention();
        assert outgoingCc.getArgumentCount() == 2;
        RegisterValue exceptionFixed = (RegisterValue) outgoingCc.getArgument(0);
        RegisterValue exceptionPcFixed = (RegisterValue) outgoingCc.getArgument(1);
        gen.emitMove(exceptionFixed, operand(exception));
        gen.emitMove(exceptionPcFixed, operand(exceptionPc));
        AArch64HotSpotJumpToExceptionHandlerInCallerOp op = new AArch64HotSpotJumpToExceptionHandlerInCallerOp(handler, exceptionFixed, exceptionPcFixed, getGen().config);
        append(op);
    }

    @Override
    public void visitFullInfopointNode(FullInfopointNode i) {
        if (i.getState() != null && i.getState().bci == BytecodeFrame.AFTER_BCI) {
            i.getDebug().log("Ignoring InfopointNode for AFTER_BCI");
        } else {
            super.visitFullInfopointNode(i);
        }
    }

    @Override
    public void visitBreakpointNode(BreakpointNode node) {
        JavaType[] sig = new JavaType[node.arguments().size()];
        for (int i = 0; i < sig.length; i++) {
            sig[i] = node.arguments().get(i).stamp(NodeView.DEFAULT).javaType(gen.getMetaAccess());
        }

        Value[] parameters = visitInvokeArguments(gen.getRegisterConfig().getCallingConvention(HotSpotCallingConventionType.JavaCall, null, sig, gen), node.arguments());
        append(new AArch64BreakpointOp(parameters));
    }

    @Override
    public ForeignCallLinkage lookupGraalStub(ValueNode valueNode, ForeignCallDescriptor foreignCallDescriptor) {
        if (getGen().getResult().getStub() != null) {
            // Emit assembly for snippet stubs
            return null;
        }
        return getGen().getForeignCalls().lookupForeignCall(foreignCallDescriptor);
    }
}