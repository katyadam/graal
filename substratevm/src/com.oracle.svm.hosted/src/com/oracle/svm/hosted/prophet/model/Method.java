package com.oracle.svm.hosted.prophet.model;

import jdk.vm.ci.meta.ResolvedJavaMethod.Parameter;

import java.lang.annotation.Annotation;
import java.util.Set;

public class Method {

    private String name;
    private String bytecodeHash;
    private Set<Parameter> parameters;
    private Set<Annotation> annotations;

    public Method(String name, byte[] bytecode, Parameter[] parameters, Annotation[] annotations) {
        this.name = name;
        this.bytecodeHash = bytesToHex(bytecode);
        this.parameters = Set.of(parameters);
        this.annotations = Set.of(annotations);
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

    public Set<Parameter> getParameters() { return parameters; }

    public void setParameters(Parameter[] parameters) { this.parameters = Set.of(parameters); }

    public Set<Annotation> getAnnotations() { return annotations; }

    public void setAnnotations(Annotation[] annotations) { this.annotations = Set.of(annotations); }

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
                parameters + "," +
                annotations;
    }
}
