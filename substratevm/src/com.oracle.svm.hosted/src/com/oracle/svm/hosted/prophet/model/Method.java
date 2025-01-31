package com.oracle.svm.hosted.prophet.model;

import com.oracle.svm.hosted.prophet.SimpleEncoder;

public class Method {

    private String name;
    private String bytecodeHash;


    public Method(String name, byte[] bytecode) {
        this.name = name;
        this.bytecodeHash = SimpleEncoder.bytesToHex(bytecode);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBytecodeHash() {
        return bytecodeHash;
    }

    public void setBytecodeHash(byte[] bytecodeHash) {
        this.bytecodeHash = SimpleEncoder.bytesToHex(bytecodeHash);
    }

    // Using "|" as divider, because part of name are commas
    @Override
    public String toString() {
        return name + "|" + bytecodeHash;
    }
}