package com.oracle.svm.hosted.prophet.model;

public class Method {

    private String name;
    private String bytecodeHash;


    public Method(String name, byte[] bytecode) {
        this.name = name;
        this.bytecodeHash = bytesToHex(bytecode);
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
        this.bytecodeHash = bytesToHex(bytecodeHash);
    }

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
        return name + "," + bytecodeHash;
    }
}
