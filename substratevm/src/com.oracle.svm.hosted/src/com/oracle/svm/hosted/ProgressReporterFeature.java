/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted;

import java.util.List;
import java.util.function.Supplier;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.impl.RuntimeReflectionSupport;

import com.oracle.svm.core.SubstrateGCOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.hosted.jdk.JNIRegistrationSupport;
import com.oracle.svm.hosted.reflect.ReflectionDataBuilder;
import com.oracle.svm.hosted.util.CPUTypeAArch64;
import com.oracle.svm.hosted.util.CPUTypeAMD64;

@AutomaticallyRegisteredFeature
public class ProgressReporterFeature implements InternalFeature {
    private final ProgressReporter reporter = ProgressReporter.singleton();

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        reporter.reportStageProgress();
    }

    protected List<UserRecommendation> getRecommendations() {
        return List.of(// in order of appearance:
                        new UserRecommendation("AWT", "Use the tracing agent to collect metadata for AWT.", ProgressReporterFeature::recommendTraceAgentForAWT),
                        new UserRecommendation("HEAP", "Set max heap for improved and more predictable memory usage.", () -> SubstrateGCOptions.MaxHeapSize.getValue() == 0),
                        new UserRecommendation("CPU", "Enable more CPU features with '-march=native' for improved performance.", ProgressReporterFeature::recommendMArchNative));
    }

    private static boolean recommendMArchNative() {
        if (NativeImageOptions.MicroArchitecture.getValue() != null) {
            return false; // explicitly set by user
        }
        if (System.getProperty("os.arch").equalsIgnoreCase("aarch64")) {
            return CPUTypeAArch64.nativeSupportsMoreFeaturesThanSelected();
        } else {
            return CPUTypeAMD64.nativeSupportsMoreFeaturesThanSelected();
        }
    }

    private static boolean recommendTraceAgentForAWT() {
        if (!ImageSingletons.contains(JNIRegistrationSupport.class)) {
            return false;
        }
        if (!JNIRegistrationSupport.singleton().isRegisteredLibrary("awt")) {
            return false; // AWT not used
        }
        // check if any method located in java.awt or sun.awt packages is registered for reflection
        ReflectionDataBuilder dataBuilder = (ReflectionDataBuilder) ImageSingletons.lookup(RuntimeReflectionSupport.class);
        return dataBuilder.getReflectionExecutables().values().stream().anyMatch(m -> {
            String className = m.getDeclaringClass().getName();
            return className.startsWith("java.awt") || className.startsWith("sun.awt");
        });
    }

    public record UserRecommendation(String id, String description, Supplier<Boolean> isApplicable) {
        public UserRecommendation(String id, String description, Supplier<Boolean> isApplicable) {
            assert id.toUpperCase().equals(id) && id.length() < 5 : "id must be uppercase and have fewer than 5 chars";
            assert description.length() < 74 : "description must have fewer than 74 chars to fit in terminal";
            this.id = id;
            this.description = description;
            this.isApplicable = isApplicable;
        }
    }
}
