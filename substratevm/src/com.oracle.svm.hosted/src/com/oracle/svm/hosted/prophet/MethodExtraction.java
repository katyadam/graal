package com.oracle.svm.hosted.prophet;

import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.prophet.model.Method;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class MethodExtraction {

    private static Set<Method> methods = new HashSet<>();

    public static Set<Method> extractClassMethods(Class<?> clazz, AnalysisMetaAccess metaAccess) {
        AnalysisType analysisType = metaAccess.lookupJavaType(clazz);

        try {
            for (AnalysisMethod declaredMethod : ((AnalysisMethod[]) analysisType.getDeclaredMethods())) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                methods.add(new Method(
                        declaredMethod.getName(),
                        digest.digest(declaredMethod.getCode())
                ));
            }
        } catch (Exception | LinkageError ex) {
            ex.printStackTrace();
        }
        return methods;
    }
}
