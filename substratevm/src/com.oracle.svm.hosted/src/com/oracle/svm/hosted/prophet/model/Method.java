package com.oracle.svm.hosted.prophet.model;

import jdk.vm.ci.meta.ResolvedJavaMethod.Parameter;

import java.util.Arrays;

public class Method {

    private String name;
    private String msName;
    private byte[] bytecodeHash;
    private Parameter[] parameters;

    public Method(String name, String msName, byte[] bytecode, Parameter[] parameters) {
        this.name = name;
        this.msName = msName;
        this.bytecodeHash = bytecode;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getBytecodeHash() {
        return bytecodeHash;
    }

    public void setBytecodeHash(byte[] bytecodeHash) {
        this.bytecodeHash = bytecodeHash;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public void setParameters(Parameter[] parameters) {
        this.parameters = parameters;
    }

    public String getMsName() {
        return msName;
    }

    public void setMsName(String msName) {
        this.msName = msName;
    }

    @Override
    public String toString() {
        return msName + "," +
                name + "," +
                bytecodeHash + "," +
                Arrays.toString(parameters);
    }
}
