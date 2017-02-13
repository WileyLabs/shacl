package org.topbraid.shacl.js;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.shacl.arq.SHACLPaths;
import org.topbraid.shacl.constraints.ComponentConstraintExecutable;
import org.topbraid.shacl.constraints.ConstraintExecutable;
import org.topbraid.shacl.constraints.ExecutionLanguage;
import org.topbraid.shacl.constraints.FailureLog;
import org.topbraid.shacl.constraints.sparql.SPARQLSubstitutions;
import org.topbraid.shacl.js.model.JSFactory;
import org.topbraid.shacl.js.model.JSTerm;
import org.topbraid.shacl.model.SHConstraint;
import org.topbraid.shacl.model.SHFactory;
import org.topbraid.shacl.model.SHJSConstraint;
import org.topbraid.shacl.model.SHJSExecutable;
import org.topbraid.shacl.model.SHParameterizableTarget;
import org.topbraid.shacl.util.SHACLUtil;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;
import org.topbraid.spin.arq.ARQFactory;
import org.topbraid.spin.util.JenaUtil;

public class JSExecutionLanguage implements ExecutionLanguage {
	
	private final static String SHACL = "SHACL";
	
	private static JSExecutionLanguage singleton = new JSExecutionLanguage();
	
	public static JSExecutionLanguage get() {
		return singleton;
	}

	
	@Override
	public SHConstraint asConstraint(Resource c) {
		return c.as(SHJSConstraint.class);
	}


	@Override
	public boolean canExecuteConstraint(ConstraintExecutable executable) {
		if(executable instanceof JSConstraintExecutable) {
			return executable.getConstraint().hasProperty(SHJS.jsFunctionName);
		}
		else if(executable instanceof ComponentConstraintExecutable) {
			ComponentConstraintExecutable cce = (ComponentConstraintExecutable)executable;
			Resource validator = cce.getValidator(SHJS.JSValidator);
			if(validator != null && (validator.hasProperty(SHJS.jsFunctionName))) {
				return true;
			}
		}
		return false;
	}

	
	@Override
	public boolean canExecuteTarget(Resource executable) {
		// TODO Auto-generated method stub
		return false;
	}

	
	@SuppressWarnings("rawtypes")
	@Override
	public boolean executeConstraint(Dataset dataset, Resource shape, URI shapesGraphURI,
			ConstraintExecutable executable, RDFNode focusNode, Resource report,
			Function<RDFNode, String> labelFunction, List<Resource> resultsList) {
		
		if(executable.getConstraint().isDeactivated()) {
			return false;
		}
		
		List<RDFNode> focusNodes;
		
		if(focusNode == null) {
			focusNodes = selectFocusNodes(shape, dataset, shapesGraphURI);
			if(focusNodes.isEmpty()) {
				// Bypass everything if set of focus nodes is empty
				return false;
			}
		}
		else {
			focusNodes = Collections.singletonList(focusNode);
		}

		JSScriptEngine engine = SHACLScriptEngineManager.getCurrentEngine();
		
		String functionName = null;
		JSGraph shapesJSGraph = new JSGraph(dataset.getNamedModel(shapesGraphURI.toString()).getGraph());
		Model dataModel = dataset.getDefaultModel();
		Object oldSHACL = engine.get(SHACL);
		engine.put(SHACL, new SHACLObject(shapesGraphURI, dataset));
		JSGraph dataJSGraph = new JSGraph(dataModel.getGraph());
		try {
			
			SHJSExecutable as = executable.getConstraint().as(SHJSExecutable.class);
			engine.executeLibraries(as);
			
			QuerySolutionMap bindings = new QuerySolutionMap();
			if(SHFactory.isPropertyShape(executable.getConstraint()) || SHFactory.isParameter(executable.getConstraint())) {
				bindings.add(SH.currentShapeVar.getVarName(), executable.getConstraint());
			}
			else if(shape != null) {
				bindings.add("currentShape", shape);
			}
			
			engine.put("$shapesGraph", shapesJSGraph);
			engine.put("$dataGraph", dataJSGraph);
			
			if(executable instanceof ComponentConstraintExecutable) {
				((ComponentConstraintExecutable)executable).addBindings(bindings);
			}

			Resource validator = null;
			if(executable instanceof JSConstraintExecutable) {
				SHJSConstraint jsc = (SHJSConstraint) executable.getConstraint();
				functionName = jsc.getFunctionName();
			}
			else {
				validator = ((ComponentConstraintExecutable)executable).getValidator(getExecutableType());
				functionName = JenaUtil.getStringProperty(validator, SHJS.jsFunctionName);
				engine.executeLibraries(validator);
			}
			
			boolean returnResult = false;
			
			for(RDFNode theFocusNode : focusNodes) {
				Object resultObj;
				bindings.add("focusNode", theFocusNode);
				
				List<RDFNode> valueNodes = new LinkedList<>();
				if(validator != null) {
					Resource component = ((ComponentConstraintExecutable)executable).getComponent();
					Resource context = ((ComponentConstraintExecutable)executable).getContext();
					if(SH.PropertyShape.equals(context)) {
						if(component.hasProperty(SH.propertyValidator, validator)) {
							bindings.add("path", executable.getConstraint().getRequiredProperty(SH.path).getObject());
							valueNodes.add(null);
						}
						else {
							Resource path = executable.getConstraint().getPropertyResourceValue(SH.path);
							valueNodes = getValueNodes(theFocusNode, path);
						}
					}
					else if(SH.NodeShape.equals(context)) {
						bindings.add("value", theFocusNode);
						valueNodes.add(theFocusNode);
					}
				}
				else {
					valueNodes.add(theFocusNode);
				}
				
				for(RDFNode valueNode : valueNodes) {
					
					bindings.add("value", valueNode);
					
					resultObj = engine.invokeFunction(functionName, bindings);
					
					if(NashornUtil.isArray(resultObj)) {
						for(Object ro : NashornUtil.asArray(resultObj)) {
							Resource result = createValidationResult(report, shape, executable, theFocusNode);
							if(ro instanceof Map) {
								Object value = ((Map)ro).get("value");
								if(value instanceof JSTerm) {
									Node resultValueNode = JSFactory.getNode(value);
									if(resultValueNode != null) {
										result.addProperty(SH.value, dataModel.asRDFNode(resultValueNode));
									}
								}
								Object message = ((Map)ro).get("message");
								if(message instanceof String) {
									result.addProperty(SH.resultMessage, (String)message);
								}
								Object path = ((Map)ro).get("path");
								if(path != null) {
									Node pathNode = JSFactory.getNode(path);
									if(pathNode != null && pathNode.isURI()) {
										result.addProperty(SH.resultPath, dataModel.asRDFNode(pathNode));
									}
								}
							}
							else if(ro instanceof String) {
								result.addProperty(SH.resultMessage, (String)ro);
							}
							if(!result.hasProperty(SH.resultMessage)) {
								addDefaultMessages(result, validator, executable, labelFunction, bindings, ro instanceof Map ? (Map)ro : null);
							}
							returnResult = true;
						}
					}
					else if(resultObj instanceof Boolean) {
						if(!(Boolean)resultObj) {
							Resource result = createValidationResult(report, shape, executable, theFocusNode);
							if(valueNode != null) {
								result.addProperty(SH.value, valueNode);
							}
							addDefaultMessages(result, validator, executable, labelFunction, bindings, null);
							resultsList.add(result);
							returnResult = true;
						}
					}
					else if(resultObj instanceof String) {
						Resource result = createValidationResult(report, shape, executable, theFocusNode);
						result.addProperty(SH.resultMessage, (String)resultObj);
						if(valueNode != null) {
							result.addProperty(SH.value, valueNode);
						}
						addDefaultMessages(result, validator, executable, labelFunction, bindings, null);
						resultsList.add(result);
						returnResult = true;
					}
				}
			}
			return returnResult;
		}
		catch(Exception ex) {
			ex.printStackTrace();
			Resource result = report.getModel().createResource(DASH.FailureResult);
			report.addProperty(SH.result, result);
			result.addProperty(SH.resultMessage, "Could not execute JavaScript constraint");
			result.addProperty(SH.sourceConstraint, executable.getConstraint());
			result.addProperty(SH.sourceShape, shape);
			if(executable instanceof ComponentConstraintExecutable) {
				result.addProperty(SH.sourceConstraintComponent, ((ComponentConstraintExecutable)executable).getComponent());
			}
			if(focusNode != null) {
				result.addProperty(SH.focusNode, focusNode);
			}
			resultsList.add(result);
			FailureLog.get().logFailure("Could not execute JavaScript function \"" + functionName + "\": " + ex);
			return true;
		}
		finally {
			dataJSGraph.close();
			shapesJSGraph.close();
			engine.put(SHACL, oldSHACL);
		}
	}
	
	
	@SuppressWarnings("rawtypes")
	private void addDefaultMessages(Resource result, Resource validator, 
				ConstraintExecutable executable, Function<RDFNode,String> labelFunction,
				QuerySolutionMap bindings, Map resultObject) {
		List<Literal> defaultMessages = executable.getMessages();
		if(defaultMessages != null) {
			for(Literal defaultMessage : defaultMessages) {
				QuerySolutionMap map = new QuerySolutionMap();
				map.addAll(bindings);
				if(resultObject != null) {
					for(Object keyObject : resultObject.keySet()) {
						String key = (String) keyObject;
						Object value = map.get(key);
						if(value != null) {
							Node valueNode = JSFactory.getNode(value);
							if(valueNode != null) {
								map.add(key, result.getModel().asRDFNode(valueNode));
							}
						}
					}
				}
				result.addProperty(SH.resultMessage, SPARQLSubstitutions.withSubstitutions(defaultMessage, map, labelFunction));
			}
		}
	}


	private Resource createValidationResult(Resource report, Resource shape, ConstraintExecutable executable,
			RDFNode focusNode) {
		Resource result = report.getModel().createResource(SH.ValidationResult);
		report.addProperty(SH.result, result);
		result.addProperty(SH.resultSeverity, SH.Violation); // TODO: Generalize
		result.addProperty(SH.sourceConstraint, executable.getConstraint());
		if(executable instanceof ComponentConstraintExecutable) {
			result.addProperty(SH.sourceConstraintComponent, ((ComponentConstraintExecutable)executable).getComponent());
		}
		else {	
			result.addProperty(SH.sourceConstraintComponent, SHJS.JSConstraintComponent);
		}
		result.addProperty(SH.sourceShape, shape);
		result.addProperty(SH.focusNode, focusNode);
		Resource path = JenaUtil.getResourceProperty(executable.getConstraint(), SH.path);
		if(path != null) {
			result.addProperty(SH.resultPath, SHACLPaths.clonePath(path, report.getModel()));
		}
		return result;
	}

	
	@Override
	public Iterable<RDFNode> executeTarget(Dataset dataset, Resource executable,
			SHParameterizableTarget parameterizableTarget) {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public Resource getConstraintComponent() {
		return SHJS.JSConstraintComponent;
	}


	@Override
	public Resource getExecutableType() {
		return SHJS.JSValidator;
	}


	@Override
	public Property getParameter() {
		return SHJS.js;
	}
	
	
	private List<RDFNode> getValueNodes(RDFNode focusNode, Resource path) {
		List<RDFNode> results = new LinkedList<RDFNode>();
		if(path.isURIResource()) {
			if(focusNode instanceof Resource) {
				StmtIterator it = focusNode.getModel().listStatements((Resource)focusNode, JenaUtil.asProperty(path), (RDFNode)null);
				while(it.hasNext()) {
					results.add(it.next().getObject());
				}
			}
		}
		else {
			String pathString = SHACLPaths.getPathString(path);
			String queryString = "SELECT DISTINCT ?value { $this " + pathString + " ?value }";
			Query query = ARQFactory.get().createQuery(path.getModel(), queryString);
			QueryExecution qexec = ARQFactory.get().createQueryExecution(query, focusNode.getModel());
			QuerySolutionMap qs = new QuerySolutionMap();
			qs.add("this", focusNode);
			qexec.setInitialBinding(qs);
			ResultSet rs = qexec.execSelect();
			while(rs.hasNext()) {
				results.add(rs.next().get("value"));
			}
			qexec.close();
		}
		return results;
	}

	
	@Override
	public boolean isNodeInTarget(RDFNode focusNode, Dataset dataset, Resource executable,
			SHParameterizableTarget parameterizableTarget) {
		// TODO Auto-generated method stub
		return false;
	}

	
	private List<RDFNode> selectFocusNodes(Resource shape, Dataset dataset, URI shapesGraphURI) {
		Set<RDFNode> results = new HashSet<RDFNode>();
		
		Model dataModel = dataset.getDefaultModel();
		
		if(JenaUtil.hasIndirectType(shape, RDFS.Class)) {
			results.addAll(JenaUtil.getAllInstances(shape.inModel(dataModel)));
		}
		
		for(Resource targetClass : JenaUtil.getResourceProperties(shape, SH.targetClass)) {
			results.addAll(JenaUtil.getAllInstances(targetClass.inModel(dataModel)));
		}
		
		results.addAll(shape.getModel().listObjectsOfProperty(shape, SH.targetNode).toList());
		
		for(Resource sof : JenaUtil.getResourceProperties(shape, SH.targetSubjectsOf)) {
			for(Statement s : dataModel.listStatements(null, JenaUtil.asProperty(sof), (RDFNode)null).toList()) {
				results.add(s.getSubject());
			}
		}
		
		for(Resource sof : JenaUtil.getResourceProperties(shape, SH.targetObjectsOf)) {
			for(Statement s : dataModel.listStatements(null, JenaUtil.asProperty(sof), (RDFNode)null).toList()) {
				results.add(s.getObject());
			}
		}
		
		for(Resource target : JenaUtil.getResourceProperties(shape, SH.target)) {
			for(RDFNode targetNode : SHACLUtil.getResourcesInTarget(target, dataset)) {
				results.add(targetNode);
			}
		}

		return new ArrayList<RDFNode>(results);
	}
}
