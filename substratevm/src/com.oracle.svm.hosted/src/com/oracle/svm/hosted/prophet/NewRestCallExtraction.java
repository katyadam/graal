package com.oracle.svm.hosted.prophet;

import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.reachability.ReachabilityAnalysisMethod;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.prophet.model.RestCall;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.vm.ci.meta.JavaConstant;

import java.util.HashSet;
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

                                for (ValueNode v : arguments) {
                                    // use traverse to extract rest call URI
                                }
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
        StringBuilder builder = new StringBuilder();
        while (currentNode != null) {
            if (currentNode instanceof InvokeWithExceptionNode &&
                    currentNode.toString().endsWith(".append")) {
                builder.append(getHeapInstanceValue((InvokeWithExceptionNode) currentNode));
            }
            currentNode = currentNode.predecessor();
        }
        return builder.toString();
    }

    private static String getHeapInstanceValue(InvokeWithExceptionNode node) throws NoSuchFieldException, IllegalAccessException {
        NodeInputList<ValueNode> arguments = node.callTarget().arguments();
        StringBuilder builder = new StringBuilder();
        for (ValueNode v : arguments) {
            if (v instanceof ConstantNode) {
                ImageHeapConstant imageHeapConstant = (ImageHeapConstant) ((ConstantNode) v).getValue();
                JavaConstant hostedObject = imageHeapConstant.getHostedObject();
                builder.append(hostedObject.toValueString());
            }
        }
        return builder.toString();
    }

}