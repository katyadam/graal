/*
 * Copyright (c) 2015, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2018, Red Hat Inc. All rights reserved.
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

import static jdk.vm.ci.code.ValueUtil.isStackSlot;
import static jdk.vm.ci.meta.JavaConstant.INT_0;
import static jdk.vm.ci.meta.JavaConstant.LONG_0;
import static jdk.compiler.graal.lir.LIRValueUtil.asConstant;
import static jdk.compiler.graal.lir.LIRValueUtil.asJavaConstant;
import static jdk.compiler.graal.lir.LIRValueUtil.isConstantValue;
import static jdk.compiler.graal.lir.LIRValueUtil.isJavaConstant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.Function;

import jdk.compiler.graal.asm.Label;
import jdk.compiler.graal.asm.aarch64.AArch64Address;
import jdk.compiler.graal.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.compiler.graal.asm.aarch64.AArch64Assembler.PrefetchMode;
import jdk.compiler.graal.asm.aarch64.AArch64MacroAssembler;
import jdk.compiler.graal.core.aarch64.AArch64ArithmeticLIRGenerator;
import jdk.compiler.graal.core.aarch64.AArch64LIRGenerator;
import jdk.compiler.graal.core.aarch64.AArch64LIRKindTool;
import jdk.compiler.graal.core.common.CompressEncoding;
import jdk.compiler.graal.core.common.LIRKind;
import jdk.compiler.graal.core.common.calc.Condition;
import jdk.compiler.graal.core.common.spi.ForeignCallLinkage;
import jdk.compiler.graal.core.common.spi.LIRKindTool;
import jdk.compiler.graal.debug.GraalError;
import jdk.compiler.graal.hotspot.GraalHotSpotVMConfig;
import jdk.compiler.graal.hotspot.HotSpotBackend;
import jdk.compiler.graal.hotspot.HotSpotDebugInfoBuilder;
import jdk.compiler.graal.hotspot.HotSpotForeignCallLinkage;
import jdk.compiler.graal.hotspot.HotSpotGraalRuntime;
import jdk.compiler.graal.hotspot.HotSpotLIRGenerationResult;
import jdk.compiler.graal.hotspot.HotSpotLIRGenerator;
import jdk.compiler.graal.hotspot.HotSpotLockStack;
import jdk.compiler.graal.hotspot.debug.BenchmarkCounters;
import jdk.compiler.graal.hotspot.meta.HotSpotProviders;
import jdk.compiler.graal.hotspot.meta.HotSpotRegistersProvider;
import jdk.compiler.graal.hotspot.stubs.Stub;
import jdk.compiler.graal.lir.ConstantValue;
import jdk.compiler.graal.lir.LIRFrameState;
import jdk.compiler.graal.lir.LIRInstruction;
import jdk.compiler.graal.lir.LabelRef;
import jdk.compiler.graal.lir.SwitchStrategy;
import jdk.compiler.graal.lir.Variable;
import jdk.compiler.graal.lir.VirtualStackSlot;
import jdk.compiler.graal.lir.aarch64.AArch64AddressValue;
import jdk.compiler.graal.lir.aarch64.AArch64Call;
import jdk.compiler.graal.lir.aarch64.AArch64ControlFlow.StrategySwitchOp;
import jdk.compiler.graal.lir.aarch64.AArch64FrameMapBuilder;
import jdk.compiler.graal.lir.aarch64.AArch64Move;
import jdk.compiler.graal.lir.aarch64.AArch64Move.StoreOp;
import jdk.compiler.graal.lir.aarch64.AArch64PrefetchOp;
import jdk.compiler.graal.lir.aarch64.AArch64RestoreRegistersOp;
import jdk.compiler.graal.lir.aarch64.AArch64SaveRegistersOp;
import jdk.compiler.graal.lir.aarch64.AArch64SpinWaitOp;
import jdk.compiler.graal.lir.gen.BarrierSetLIRGenerator;
import jdk.compiler.graal.lir.gen.LIRGenerationResult;
import jdk.compiler.graal.lir.gen.MoveFactory;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.meta.Value;

/**
 * LIR generator specialized for AArch64 HotSpot.
 */
public class AArch64HotSpotLIRGenerator extends AArch64LIRGenerator implements HotSpotLIRGenerator {

    final GraalHotSpotVMConfig config;
    private HotSpotDebugInfoBuilder debugInfoBuilder;

    protected static BarrierSetLIRGenerator getBarrierSet(GraalHotSpotVMConfig config, HotSpotProviders providers) {
        if (config.gc == HotSpotGraalRuntime.HotSpotGC.Z) {
            return new AArch64HotSpotZBarrierSetLIRGenerator(config, providers);
        }
        return null;
    }

    protected AArch64HotSpotLIRGenerator(HotSpotProviders providers, GraalHotSpotVMConfig config, LIRGenerationResult lirGenRes) {
        this(new AArch64LIRKindTool(), new AArch64ArithmeticLIRGenerator(null), getBarrierSet(config, providers), new AArch64HotSpotMoveFactory(), providers, config, lirGenRes);
    }

    protected AArch64HotSpotLIRGenerator(LIRKindTool lirKindTool, AArch64ArithmeticLIRGenerator arithmeticLIRGen, BarrierSetLIRGenerator barrierSetLIRGen, MoveFactory moveFactory,
                    HotSpotProviders providers, GraalHotSpotVMConfig config, LIRGenerationResult lirGenRes) {
        super(lirKindTool, arithmeticLIRGen, barrierSetLIRGen, moveFactory, providers, lirGenRes);
        this.config = config;
    }

    @Override
    public HotSpotProviders getProviders() {
        return (HotSpotProviders) super.getProviders();
    }

    @Override
    public boolean needOnlyOopMaps() {
        // Stubs only need oop maps
        return getResult().getStub() != null;
    }

    @Override
    protected Value getCompareValueForConstantPointer(Value v) {
        if (isConstantValue(v)) {
            Constant c = asConstant(v);
            if (JavaConstant.isNull(c)) {
                /*
                 * On HotSpot null values can be represented by the zero value of appropriate
                 * length.
                 */
                var platformKind = v.getPlatformKind();
                assert platformKind.equals(AArch64Kind.DWORD) || platformKind.equals(AArch64Kind.QWORD) : String.format("unexpected null value: %s[%s]", platformKind, v);
                return new ConstantValue(LIRKind.value(platformKind), platformKind.getSizeInBytes() == Integer.BYTES ? INT_0 : LONG_0);
            } else if (c instanceof HotSpotObjectConstant) {
                return asAllocatable(v);
            }
        }
        return super.getCompareValueForConstantPointer(v);
    }

    private LIRFrameState currentRuntimeCallInfo;

    @Override
    protected void emitForeignCallOp(ForeignCallLinkage linkage, Value targetAddress, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        currentRuntimeCallInfo = info;
        if (AArch64Call.isNearCall(linkage)) {
            append(new AArch64Call.DirectNearForeignCallOp(linkage, result, arguments, temps, info, label));
        } else {
            append(new AArch64Call.DirectFarForeignCallOp(linkage, result, arguments, temps, info, label));
        }
    }

    /**
     * @param savedRegisters the registers saved by this operation which may be subject to pruning
     * @param savedRegisterLocations the slots to which the registers are saved
     */
    protected AArch64SaveRegistersOp emitSaveRegisters(Register[] savedRegisters, AllocatableValue[] savedRegisterLocations) {
        AArch64SaveRegistersOp save = new AArch64SaveRegistersOp(savedRegisters, savedRegisterLocations);
        append(save);
        return save;
    }

    /**
     * Allocate a stack slot for saving a register.
     */
    protected VirtualStackSlot allocateSaveRegisterLocation(Register register) {
        PlatformKind kind = target().arch.getLargestStorableKind(register.getRegisterCategory());
        if (kind.getVectorLength() > 1) {
            // we don't use vector registers, so there is no need to save them
            kind = AArch64Kind.DOUBLE;
        }
        return getResult().getFrameMapBuilder().allocateSpillSlot(LIRKind.value(kind));
    }

    /**
     * Adds a node to the graph that saves all allocatable registers to the stack.
     *
     * @return the register save node
     */
    private AArch64SaveRegistersOp emitSaveAllRegisters(Register[] savedRegisters) {
        AllocatableValue[] savedRegisterLocations = new AllocatableValue[savedRegisters.length];
        for (int i = 0; i < savedRegisters.length; i++) {
            savedRegisterLocations[i] = allocateSaveRegisterLocation(savedRegisters[i]);
        }
        return emitSaveRegisters(savedRegisters, savedRegisterLocations);
    }

    protected void emitRestoreRegisters(AArch64SaveRegistersOp save) {
        append(new AArch64RestoreRegistersOp(save.getSlots().clone(), save));
    }

    @Override
    public VirtualStackSlot getLockSlot(int lockDepth) {
        return getLockStack().makeLockSlot(lockDepth);
    }

    private HotSpotLockStack getLockStack() {
        assert debugInfoBuilder != null && debugInfoBuilder.lockStack() != null;
        return debugInfoBuilder.lockStack();
    }

    @Override
    public Value emitCompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        LIRKind inputKind = pointer.getValueKind(LIRKind.class);
        assert inputKind.getPlatformKind() == AArch64Kind.QWORD;
        if (inputKind.isReference(0)) {
            // oop
            Variable result = newVariable(LIRKind.compressedReference(AArch64Kind.DWORD));
            append(new AArch64HotSpotMove.CompressPointer(result, asAllocatable(pointer), getProviders().getRegisters().getHeapBaseRegister().asValue(), encoding, nonNull));
            return result;
        } else {
            // metaspace pointer
            Variable result = newVariable(LIRKind.value(AArch64Kind.DWORD));
            AllocatableValue base = Value.ILLEGAL;
            if (encoding.hasBase()) {
                base = emitLoadConstant(LIRKind.value(AArch64Kind.QWORD), JavaConstant.forLong(encoding.getBase()));
            }
            append(new AArch64HotSpotMove.CompressPointer(result, asAllocatable(pointer), base, encoding, nonNull));
            return result;
        }
    }

    @Override
    public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        LIRKind inputKind = pointer.getValueKind(LIRKind.class);
        assert inputKind.getPlatformKind() == AArch64Kind.DWORD;
        if (inputKind.isReference(0)) {
            // oop
            Variable result = newVariable(LIRKind.reference(AArch64Kind.QWORD));
            append(new AArch64HotSpotMove.UncompressPointer(result, asAllocatable(pointer), getProviders().getRegisters().getHeapBaseRegister().asValue(), encoding, nonNull));
            return result;
        } else {
            // metaspace pointer
            Variable result = newVariable(LIRKind.value(AArch64Kind.QWORD));
            AllocatableValue base = Value.ILLEGAL;
            if (encoding.hasBase()) {
                base = emitLoadConstant(LIRKind.value(AArch64Kind.QWORD), JavaConstant.forLong(encoding.getBase()));
            }
            append(new AArch64HotSpotMove.UncompressPointer(result, asAllocatable(pointer), base, encoding, nonNull));
            return result;
        }
    }

    @Override
    public void emitNullCheck(Value address, LIRFrameState state) {
        if (address.getValueKind().getPlatformKind() == AArch64Kind.DWORD) {
            CompressEncoding encoding = config.getOopEncoding();
            Value uncompressed = emitUncompress(address, encoding, false);
            append(new AArch64Move.NullCheckOp(asAddressValue(uncompressed, AArch64Address.ANY_SIZE), state));
        } else {
            super.emitNullCheck(address, state);
        }
    }

    /**
     * Within {@link AArch64HotSpotCounterOp} ADDS is used to perform the benchmark counter
     * increment. Thus, in order for a constant to be directly used, it must fit in the immediate
     * operand of this instruction.
     */
    private Value transformBenchmarkCounterIncrement(Value increment) {
        if (isJavaConstant(increment) && AArch64ArithmeticLIRGenerator.isAddSubtractConstant(asJavaConstant(increment))) {
            return increment;
        } else {
            return asAllocatable(increment);
        }
    }

    @Override
    public LIRInstruction createBenchmarkCounter(String name, String group, Value increment) {
        if (BenchmarkCounters.enabled) {
            return new AArch64HotSpotCounterOp(name, group, transformBenchmarkCounterIncrement(increment), getProviders().getRegisters(), config);
        }
        throw GraalError.shouldNotReachHere("BenchmarkCounters are not enabled!"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createMultiBenchmarkCounter(String[] names, String[] groups, Value[] increments) {
        if (BenchmarkCounters.enabled) {
            Value[] incrementValues = Arrays.stream(increments).map(this::transformBenchmarkCounterIncrement).toArray(Value[]::new);
            return new AArch64HotSpotCounterOp(names, groups, incrementValues, getProviders().getRegisters(), config);
        }
        throw GraalError.shouldNotReachHere("BenchmarkCounters are not enabled!"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitPrefetchAllocate(Value address) {
        append(new AArch64PrefetchOp(asAddressValue(address, AArch64Address.ANY_SIZE), PrefetchMode.PSTL1KEEP));
    }

    @Override
    public void beforeRegisterAllocation() {
        super.beforeRegisterAllocation();
        boolean hasDebugInfo = getResult().getLIR().hasDebugInfo();
        if (hasDebugInfo) {
            getResult().setDeoptimizationRescueSlot(((AArch64FrameMapBuilder) getResult().getFrameMapBuilder()).allocateDeoptimizationRescueSlot());
        }

        getResult().setMaxInterpreterFrameSize(debugInfoBuilder.maxInterpreterFrameSize());
    }

    private Label label;

    @Override
    public Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, Value... args) {
        HotSpotForeignCallLinkage hotspotLinkage = (HotSpotForeignCallLinkage) linkage;
        boolean destroysRegisters = hotspotLinkage.destroysRegisters();

        AArch64SaveRegistersOp save = null;
        Stub stub = getStub();
        if (destroysRegisters && stub != null && stub.shouldSaveRegistersAroundCalls()) {
            Register[] savedRegisters = getRegisterConfig().getAllocatableRegisters().toArray();
            save = emitSaveAllRegisters(savedRegisters);
        }

        Variable result;
        LIRFrameState debugInfo = null;
        if (hotspotLinkage.needsDebugInfo()) {
            debugInfo = state;
            assert debugInfo != null || getStub() != null;
        }

        if (destroysRegisters || hotspotLinkage.needsJavaFrameAnchor()) {
            HotSpotRegistersProvider registers = getProviders().getRegisters();
            Register thread = registers.getThreadRegister();
            Variable scratch = newVariable(LIRKind.value(target().arch.getWordKind()));

            // We need a label for the return address.
            label = new Label();

            append(new AArch64HotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), thread, scratch, label));
            result = super.emitForeignCall(hotspotLinkage, debugInfo, args);
            append(new AArch64HotSpotCRuntimeCallEpilogueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), thread, label));

            // Clear it out so it's not being reused later.
            label = null;
        } else {
            result = super.emitForeignCall(hotspotLinkage, debugInfo, args);
        }

        // Handle different return value locations
        if (stub != null && stub.getLinkage().getEffect() == HotSpotForeignCallLinkage.RegisterEffect.KILLS_NO_REGISTERS && result != null) {
            CallingConvention inCC = stub.getLinkage().getIncomingCallingConvention();
            if (!inCC.getReturn().equals(linkage.getOutgoingCallingConvention().getReturn())) {
                assert isStackSlot(inCC.getReturn());
                emitMove(inCC.getReturn(), result);
            }
        }

        if (save != null) {
            HotSpotLIRGenerationResult generationResult = getResult();
            LIRFrameState key = currentRuntimeCallInfo;
            if (key == null) {
                key = LIRFrameState.noCalleeSaveInfo();
            }
            assert !generationResult.getCalleeSaveInfo().containsKey(key);
            generationResult.getCalleeSaveInfo().put(key, save);
            emitRestoreRegisters(save);
        }

        return result;
    }

    @Override
    public void emitDeoptimizeCaller(DeoptimizationAction action, DeoptimizationReason reason) {
        Value actionAndReason = emitJavaConstant(getMetaAccess().encodeDeoptActionAndReason(action, reason, 0));
        Value speculation = emitJavaConstant(getMetaAccess().encodeSpeculation(SpeculationLog.NO_SPECULATION));
        moveDeoptValuesToThread(actionAndReason, speculation);
        append(new AArch64HotSpotDeoptimizeCallerOp(config));
    }

    @Override
    public void emitDeoptimizeWithExceptionInCaller(Value exception) {
        Register thread = getProviders().getRegisters().getThreadRegister();
        append(new AArch64HotSpotDeoptimizeWithExceptionCallerOp(config, exception, thread));
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value failedSpeculation, LIRFrameState state) {
        moveDeoptValuesToThread(actionAndReason, failedSpeculation);
        append(new AArch64HotSpotDeoptimizeOp(state));
    }

    private void moveDeoptValuesToThread(Value actionAndReason, Value speculation) {
        moveValueToThread(actionAndReason, config.pendingDeoptimizationOffset);
        moveValueToThread(speculation, config.pendingFailedSpeculationOffset);
    }

    private void moveValueToThread(Value value, int offset) {
        LIRKind wordKind = LIRKind.value(target().arch.getWordKind());
        int bitMemoryTransferSize = value.getValueKind().getPlatformKind().getSizeInBytes() * Byte.SIZE;
        RegisterValue thread = getProviders().getRegisters().getThreadRegister().asValue(wordKind);
        AArch64AddressValue address = AArch64AddressValue.makeAddress(wordKind, bitMemoryTransferSize, thread, offset);
        append(new StoreOp((AArch64Kind) value.getPlatformKind(), address, asAllocatable(value), null));
    }

    @Override
    public void emitUnwind(Value exception) {
        ForeignCallLinkage linkage = getForeignCalls().lookupForeignCall(HotSpotBackend.UNWIND_EXCEPTION_TO_CALLER);
        CallingConvention outgoingCc = linkage.getOutgoingCallingConvention();
        assert outgoingCc.getArgumentCount() == 2;
        RegisterValue exceptionParameter = (RegisterValue) outgoingCc.getArgument(0);
        emitMove(exceptionParameter, exception);
        append(new AArch64HotSpotUnwindOp(config, exceptionParameter));
    }

    @Override
    public void emitReturn(JavaKind kind, Value input) {
        AllocatableValue operand = Value.ILLEGAL;
        if (input != null) {
            operand = resultOperandFor(kind, input.getValueKind());
            emitMove(operand, input);
        }
        Register thread = getProviders().getRegisters().getThreadRegister();
        append(new AArch64HotSpotReturnOp(operand, getStub() != null, config, thread, getResult().requiresReservedStackAccessCheck()));
    }

    /**
     * Gets the {@link Stub} this generator is generating code for or {@code null} if a stub is not
     * being generated.
     */
    public Stub getStub() {
        return getResult().getStub();
    }

    @Override
    public HotSpotLIRGenerationResult getResult() {
        return ((HotSpotLIRGenerationResult) super.getResult());
    }

    @Override
    protected StrategySwitchOp createStrategySwitchOp(SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, AllocatableValue key, Function<Condition, ConditionFlag> converter) {
        return new AArch64HotSpotStrategySwitchOp(strategy, keyTargets, defaultTarget, key, converter);
    }

    public void setDebugInfoBuilder(HotSpotDebugInfoBuilder debugInfoBuilder) {
        this.debugInfoBuilder = debugInfoBuilder;
    }

    @Override
    public void emitZeroMemory(Value address, Value length, boolean isAligned) {
        final EnumSet<AArch64.Flag> flags = ((AArch64) target().arch).getFlags();

        int zvaLength = config.zvaLength;
        boolean isDcZvaProhibited = 0 == zvaLength;

        // Use DC ZVA if it's not prohibited and AArch64 HotSpot flag UseBlockZeroing is on.
        boolean useDcZva = !isDcZvaProhibited && flags.contains(AArch64.Flag.UseBlockZeroing);

        emitZeroMemory(address, length, isAligned, useDcZva, zvaLength);
    }

    private Consumer<AArch64MacroAssembler> onSpinWaitInst() {
        return switch (config.onSpinWaitInst) {
            case "nop" -> AArch64MacroAssembler::nop;
            case "isb" -> AArch64MacroAssembler::isb;
            case "yield" -> AArch64MacroAssembler::pause;
            default -> throw GraalError.shouldNotReachHere("Unknown OnSpinWaitInst " + config.onSpinWaitInst);
        };
    }

    @Override
    public void emitSpinWait() {
        if ("none".equals(config.onSpinWaitInst)) {
            // do nothing
        } else {
            GraalError.guarantee(config.onSpinWaitInstCount > 0, "illegal onSpinWaitInstCount");
            append(new AArch64SpinWaitOp(onSpinWaitInst(), config.onSpinWaitInstCount));
        }
    }

    @Override
    public int getArrayLengthOffset() {
        return config.arrayOopDescLengthOffset();
    }

    @Override
    public Register getHeapBaseRegister() {
        return getProviders().getRegisters().getHeapBaseRegister();
    }

    @Override
    protected int getVMPageSize() {
        return config.vmPageSize;
    }

    @Override
    protected int getSoftwarePrefetchHintDistance() {
        return config.softwarePrefetchHintDistance;
    }
}