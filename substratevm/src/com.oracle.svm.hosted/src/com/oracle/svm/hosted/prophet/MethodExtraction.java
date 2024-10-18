package com.oracle.svm.hosted.prophet;

import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.prophet.model.Method;

import java.security.MessageDigest;
import java.util.Set;

public class MethodExtraction {

    private static Set<Method> methods;

    public static Set<Method> extractClassRestCalls(Class<?> clazz, AnalysisMetaAccess metaAccess, String msName) {
        AnalysisType analysisType = metaAccess.lookupJavaType(clazz);

        try {
            for (AnalysisMethod declaredMethod : ((AnalysisMethod[]) analysisType.getDeclaredMethods())) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");

                methods.add(new Method(
                        declaredMethod.getName(),
                        msName,
                        digest.digest(declaredMethod.getCode()),
                        declaredMethod.getParameters()
                ));
            }
        } catch (Exception | LinkageError ex) {
            ex.printStackTrace();
        }
        return methods;
    }
}
