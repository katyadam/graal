package com.oracle.svm.hosted.prophet;

import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.reachability.ReachabilityAnalysisMethod;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.prophet.model.RESTParameter;
import com.oracle.svm.hosted.prophet.model.RestCall;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.vm.ci.meta.JavaConstant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NewRestCallExtraction {

    private final static String REST_TEMPLATE_PACKAGE = "org.springframework.web.client.RestTemplate.";
    private final static String HTTP_ENTITY_PACKAGE = "org.springframework.http.HttpEntity";
    private static Set<RestCall> restCalls = new HashSet<>();

    public static Set<RestCall> extractClassRestCalls(Class<?> clazz, AnalysisMetaAccess metaAccess, Inflation bb, Map<String, Object> propMap, String msName) {
        AnalysisType analysisType = metaAccess.lookupJavaType(clazz);
        try {
            for (AnalysisMethod method : ((AnalysisMethod[]) analysisType.getDeclaredMethods())) {
                try {
                    StructuredGraph decodedGraph = ReachabilityAnalysisMethod.getDecodedGraph(bb, method);
                    for (Node node : decodedGraph.getNodes()) {
                        if (node instanceof Invoke) {
                            Invoke invoke = (Invoke) node;
                            AnalysisMethod targetMethod = ((AnalysisMethod) invoke.getTargetMethod());
                            if (targetMethod.getQualifiedName().startsWith(REST_TEMPLATE_PACKAGE)) {
                                CallTargetNode callTargetNode = invoke.callTarget();
                                NodeInputList<ValueNode> arguments = callTargetNode.arguments();

                                String HTTP_METHOD_TYPE = parseHttpMethodType(targetMethod.getQualifiedName());
                                String PARENT_METHOD = cleanParentMethod(method.getQualifiedName());
                                String RETURN_TYPE = null;
                                StringBuilder URI = new StringBuilder();
                                Boolean callIsCollection = false;

                                for (ValueNode v : arguments) {
                                    // use traverse to extract rest call URI
                                    URI.append(traverse(v));
                                }
                                // RestTemplate is an EXCHANGE, get specific HTTP type
                                if (HTTP_METHOD_TYPE != null && HTTP_METHOD_TYPE.equals("EXCHANGE")) {
                                    HTTP_METHOD_TYPE = extractHttpType(callTargetNode);
                                }
                                // TO-DO: In future try to get what type of HTTP Entity.
                                if (RETURN_TYPE == null || RETURN_TYPE.contains("edu.fudan.common.util.Response")) {
                                    RETURN_TYPE = HTTP_ENTITY_PACKAGE;
                                }
                                RESTParameter param = getParamDetails(callTargetNode, URI.toString());
                                restCalls.add(new RestCall(HTTP_METHOD_TYPE, PARENT_METHOD, RETURN_TYPE, URI.toString(), callIsCollection, clazz.getCanonicalName(), msName, param));

                            }
                        }
                    }
                } catch (Exception | LinkageError ex) {
                    ex.printStackTrace();
                }
            }
        } catch (Exception |
                 LinkageError ex) {
            ex.printStackTrace();
        }
        return restCalls;
    }

    // ((ImageHeapInstance)((ConstantNode)(((InvokeWithExceptionNode) (arguments.get(1)).predecessor().predecessor()).callTarget().arguments().get(1))).getValue()).getConstantData().hostedObject.toValueString()
    private static String traverse(ValueNode node) throws NoSuchFieldException, IllegalAccessException {
        Node currentNode = node;
        List<String> uriParts = new ArrayList<>();
        while (currentNode != null) {
            if (currentNode instanceof InvokeWithExceptionNode &&
                    currentNode.toString().endsWith(".append")) {
                uriParts.add(getHeapInstanceValue((InvokeWithExceptionNode) currentNode).replace("\"", ""));
            }
            currentNode = currentNode.predecessor();
        }
        return String.join("", uriParts.reversed());
    }

    private static String getHeapInstanceValue(InvokeWithExceptionNode node) {
        NodeInputList<ValueNode> arguments = node.callTarget().arguments();
        StringBuilder builder = new StringBuilder();
        for (ValueNode v : arguments) {
            if (v instanceof ConstantNode) {
                ImageHeapConstant imageHeapConstant = (ImageHeapConstant) ((ConstantNode) v).getValue();
                JavaConstant hostedObject = imageHeapConstant.getHostedObject();
                builder.append(hostedObject.toValueString());
            } else if (v instanceof InvokeWithExceptionNode) {
                builder.append(getHeapInstanceValue((InvokeWithExceptionNode) v));
            }
        }
        return builder.toString();
    }

    /**
     * given a target method's qualified name, return http method type
     *
     * @param input targe method's qualified name
     * @return http method type extracted
     */
    private static String parseHttpMethodType(String input) {
        Map<String, String> httpMethodTypes = new HashMap<>();
        httpMethodTypes.put("getFor", "GET");
        httpMethodTypes.put("postFor", "POST");
        httpMethodTypes.put("delete", "DELETE");
        httpMethodTypes.put("exchange", "EXCHANGE");

        String httpMethodType = null;
        String inputSubStr = input.substring(REST_TEMPLATE_PACKAGE.length());
        inputSubStr = inputSubStr.substring(0, inputSubStr.indexOf("("));

        // iterate over map of http method types and verify http method type
        for (Map.Entry<String, String> entry : httpMethodTypes.entrySet()) {
            if (inputSubStr.startsWith(entry.getKey())) {
                httpMethodType = entry.getValue();
                break;
            }
        }
        return httpMethodType;
    }

    private static String extractHttpType(CallTargetNode node) {
        String httpType = "";
        for (ValueNode arg : node.arguments()) {
            if (arg instanceof Invoke) {
                httpType = extractHttpType(((Invoke) arg).callTarget());
            } else if (arg instanceof PiNode) {
                for (Node inputNode : ((PiNode) arg).inputs()) {
                    if (inputNode instanceof Invoke) {
                        httpType = extractHttpType(((Invoke) inputNode).callTarget());
                    }
                }
            } else if (arg instanceof LoadFieldNode) {
                LoadFieldNode loadfieldNode = (LoadFieldNode) arg;
                AnalysisField field = (AnalysisField) loadfieldNode.field();
                if (field.getDeclaringClass().getName().contains("HttpMethod")) {
                    httpType = field.getName();
                }
            }
        }
        return httpType;
    }

    /**
     * extract the method the rest call is being in
     *
     * @param input the method's qualified name
     * @return the method the call is being made in
     */
    private static String cleanParentMethod(String input) {
        String parentMethod = null;

        parentMethod = input.substring(0, input.indexOf("("));
        return parentMethod;
    }

    private static RESTParameter getParamDetails(CallTargetNode node, String URI) {

        RESTParameter param = new RESTParameter(false, false);
        // check if URI has slashes then it has path parameters
        // check for slashes at end of string
        int count = 0;
        boolean slashFound = false;
        for (int i = 0; i < URI.length(); i++) {
            char c = URI.charAt(i);
            if (i == URI.length() - 1 && c == '/') {
                count++;
            } else if (c == '/' && URI.charAt(i + 1) == '/') {
                count++;
            }
        }
        param.setParamCount(count);
        if (count > 0) {
            param.setIsPath(true);
        }
        param = setIfBodyAndType(param, node);

        return param;
    }

    // assumes there is only one HTTP_ENTITY object in each REST call method
    private static RESTParameter setIfBodyAndType(RESTParameter param, CallTargetNode node) {
        for (ValueNode arg : node.arguments()) {
            if (arg instanceof PiNode) {
                for (Node inputNode : ((PiNode) arg).inputs()) {
                    if (inputNode instanceof Invoke) {
                        param = setIfBodyAndType(param, ((Invoke) inputNode).callTarget());
                    }
                }
            } else if (arg instanceof Invoke) {
                param = handleIfInvokeInRESTParam(param, arg);
            }
        }
        return param;
    }

    // nodes passed into here are only if they are instanceof Invoke
    private static RESTParameter handleIfInvokeInRESTParam(RESTParameter param, ValueNode node) {
        for (Node inNode : node.inputs()) {
            if (inNode instanceof Invoke) {
                param = handleIfInvokeInRESTParam(param, ((ValueNode) inNode));
            }
        }
        Node predecessor = node.predecessor();
        if (predecessor instanceof BeginNode && predecessor.predecessor() instanceof Invoke) {
            Node bNodePredecessor = predecessor.predecessor();

            if (((Invoke) predecessor.predecessor()).callTarget().targetMethod().toString().contains(HTTP_ENTITY_PACKAGE)) {
                int inputAmnt = 0;
                for (Node ctIn : ((Invoke) predecessor.predecessor()).callTarget().inputs()) {
                    inputAmnt++;
                }

                // if virtualnode(?) has a zero but Allocated node has 3 inputs, there is a body
                // with param. Seems there is always two inputs by default. Whatever the inputs
                // minus 2 is how many params I think
                int paramCount = inputAmnt - 2;
                if (paramCount > 0) {
                    param.setParamCount(param.getParamCount() + paramCount);
                    param.setIsBody(true);
                }
                CommitAllocationNode caNode = (CommitAllocationNode) bNodePredecessor.predecessor();
            }
            param = setIfBodyAndType(param, ((Invoke) predecessor.predecessor()).callTarget());
        } else {
            param = setIfBodyAndType(param, ((Invoke) node).callTarget());
        }
        return param;
    }

}