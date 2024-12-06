package com.oracle.svm.hosted.prophet;

import com.oracle.svm.hosted.prophet.model.Endpoint;
import com.oracle.svm.hosted.prophet.model.Method;
import com.oracle.svm.hosted.prophet.model.RestCall;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

public class RestDump {

    //RESTCALL CSV ORDER SHOULD BE
    //msName, restCallInClassName, parentMethod, uri, httpMethod, returnType, isPath, isBody, paramType, paramCount, isCollection
    //ENDPOINT CSV ORDER SHOULD BE
    //msName, endpointInClassName, parentMethod, arguments, path, httpMethod, returnType, isCollection
    //METHOD CSV ORDER SHOULD BE
    //name, bytecodeHash, parameters, annotations

    //TO-DO: add header row to csv output!!!
    public void writeOutRestCalls(Set<RestCall> restCalls, String outputFile) {
        if (outputFile == null) {
            throw new RuntimeException("ProphetRestCallOutputFile option was not provided");
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (RestCall rc : restCalls) {
                writer.write(rc.toString() + "\n");
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public void writeOutEndpoints(Set<Endpoint> endpoints, String outputFile) {
        if (outputFile == null) {
            throw new RuntimeException("ProphetEndpointOutputFile option was not provided");
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (Endpoint ep : endpoints) {
                writer.write(ep.toString() + "\n");
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void writeOutMethods(Set<Method> methods, String outputFile) {
        if (outputFile == null) {
            throw new RuntimeException("ProphetMethodOutputFile option was not provided");
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (Method m : methods) {
                writer.write(m.toString() + "\n");
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}