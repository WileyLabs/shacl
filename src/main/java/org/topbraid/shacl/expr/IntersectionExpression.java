/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 */
package org.topbraid.shacl.expr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.RDFNode;

public class IntersectionExpression extends ComplexNodeExpression {
	
	private List<NodeExpression> inputs;
	
	
	public IntersectionExpression(List<NodeExpression> inputs) {
		this.inputs = inputs;
	}

	
	@Override
	public void appendLabel(AppendContext context, String targetVarName) {
		String varName = context.getNextVarName();
		for(int i = 0; i < inputs.size(); i++) {
			NodeExpression input = inputs.get(i);
			if(input instanceof ComplexNodeExpression) {
				((ComplexNodeExpression)input).appendLabel(context, varName + (i + 1));
			}
			else {
				context.indent();
				context.append("BIND (");
				context.append(input.toString());
				context.append(" AS ?");
				context.append(varName + (i + 1));
				context.append(") .\n");
			}
		}
		context.indent();
		context.append("FILTER (bound(?");
		context.append(varName);
		context.append("1) ");
		for(int i = 1; i < inputs.size(); i++) {
			context.append(" && ?");
			context.append(varName + (i + 1));
			context.append("=?");
			context.append(varName + "1");
		}
		context.append(") .\n");
		context.indent();
		context.append("BIND (?");
		context.append(varName);
		context.append("1 AS ?");
		context.append(targetVarName);
		context.append(") .\n");
	}


	@Override
	public List<RDFNode> eval(RDFNode focusNode, NodeExpressionContext context) {
		Iterator<NodeExpression> it = inputs.iterator();
		Set<RDFNode> results = new HashSet<RDFNode>(it.next().eval(focusNode, context));
		while(it.hasNext()) {
			results.retainAll(it.next().eval(focusNode, context));
		}
		return new ArrayList<RDFNode>(results);
	}
}
