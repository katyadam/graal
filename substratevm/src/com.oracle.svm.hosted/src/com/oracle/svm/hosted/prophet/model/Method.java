package com.oracle.svm.hosted.prophet.model;

import jdk.vm.ci.meta.ResolvedJavaMethod.Parameter;

import java.lang.annotation.Annotation;
import java.util.Arrays;

public class Method {

    private String name;
    private String bytecodeHash;
    private Parameter[] parameters;
    private Annotation[] annotations;

    public Method(String name, byte[] bytecode, Parameter[] parameters, Annotation[] annotations) {
        this.name = name;
        this.bytecodeHash = bytesToHex(bytecode);
        this.parameters = parameters;
        this.annotations = annotations;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBytecodeHash() { return bytecodeHash; }

    public void setBytecodeHash(byte[] bytecodeHash) {
        this.bytecodeHash = bytesToHex(bytecodeHash);
    }

    public Parameter[] getParameters() { return parameters; }

    public void setParameters(Parameter[] parameters) { this.parameters = parameters; }

    public Annotation[] getAnnotations() { return annotations; }

    public void setAnnotations(Annotation[] annotations) { this.annotations = annotations; }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @Override
    public String toString() {
        return name + "," +
                bytecodeHash + "," +
                Arrays.toString(parameters) + "," +
                Arrays.toString(annotations);
    }
}
