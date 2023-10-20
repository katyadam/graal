/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.compiler.graal.hotspot.riscv64;

import static jdk.vm.ci.meta.Value.ILLEGAL;
import static jdk.compiler.graal.hotspot.HotSpotBackend.EXCEPTION_HANDLER;
import static jdk.compiler.graal.hotspot.HotSpotBackend.EXCEPTION_HANDLER_IN_CALLER;
import static jdk.compiler.graal.hotspot.HotSpotForeignCallLinkage.JUMP_ADDRESS;
import static jdk.compiler.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.DESTROYS_ALL_CALLER_SAVE_REGISTERS;

import jdk.compiler.graal.core.common.LIRKind;
import jdk.compiler.graal.core.riscv64.ShadowedRISCV64;
import jdk.compiler.graal.hotspot.HotSpotForeignCallLinkageImpl;
import jdk.compiler.graal.hotspot.HotSpotGraalRuntimeProvider;
import jdk.compiler.graal.hotspot.meta.HotSpotHostForeignCallsProvider;
import jdk.compiler.graal.hotspot.meta.HotSpotProviders;
import jdk.compiler.graal.hotspot.word.HotSpotWordTypes;
import jdk.compiler.graal.options.OptionValues;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.hotspot.HotSpotCodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;

public class RISCV64HotSpotForeignCallsProvider extends HotSpotHostForeignCallsProvider {

    private final Value[] nativeABICallerSaveRegisters;

    public RISCV64HotSpotForeignCallsProvider(HotSpotJVMCIRuntime jvmciRuntime, HotSpotGraalRuntimeProvider graalRuntime, MetaAccessProvider metaAccess,
                    HotSpotCodeCacheProvider codeCache, HotSpotWordTypes wordTypes, Value[] nativeABICallerSaveRegisters) {
        super(jvmciRuntime, graalRuntime, metaAccess, codeCache, wordTypes);
        this.nativeABICallerSaveRegisters = nativeABICallerSaveRegisters;
    }

    @Override
    public void initialize(HotSpotProviders providers, OptionValues options) {
        TargetDescription target = providers.getCodeCache().getTarget();
        PlatformKind word = target.arch.getWordKind();

        // The calling convention for the exception handler stub is (only?) defined in
        // TemplateInterpreterGenerator::generate_throw_exception()
        RegisterValue exception = ShadowedRISCV64.x10.asValue(LIRKind.reference(word));
        RegisterValue exceptionPc = ShadowedRISCV64.x13.asValue(LIRKind.value(word));
        CallingConvention exceptionCc = new CallingConvention(0, ILLEGAL, exception, exceptionPc);
        register(new HotSpotForeignCallLinkageImpl(EXCEPTION_HANDLER, 0L, DESTROYS_ALL_CALLER_SAVE_REGISTERS, exceptionCc, null));
        register(new HotSpotForeignCallLinkageImpl(EXCEPTION_HANDLER_IN_CALLER, JUMP_ADDRESS, DESTROYS_ALL_CALLER_SAVE_REGISTERS, exceptionCc, null));

        super.initialize(providers, options);
    }

    @Override
    public Value[] getNativeABICallerSaveRegisters() {
        return nativeABICallerSaveRegisters;
    }
}