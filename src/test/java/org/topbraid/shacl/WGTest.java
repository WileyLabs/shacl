package org.topbraid.shacl;

import java.io.InputStream;
import java.net.URI;
import java.util.logging.Logger;

import com.sun.corba.se.impl.presentation.rmi.ExceptionHandler;
import junit.framework.Test;
import junit.framework.TestSuite;

import org.apache.log4j.BasicConfigurator;
import org.topbraid.shacl.constraints.FailureLog;
import org.topbraid.spin.util.JenaUtil;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.util.FileUtils;
import org.apache.jena.vocabulary.RDF;

public class WGTest extends TestSuite {
	
	public WGTest() throws Exception {
//		BasicConfigurator.configure();
		String baseURI = getClass().getResource("/manifest.ttl").toURI().toString();
//		String baseURI = getClass().getResource("/features/implementation/manifest.ttl").toURI().toString();
		collectTestCases(baseURI);
	}
	
	
    public static Test suite() throws Exception {
    	FailureLog.set(new FailureLog() {
			@Override
			public void logFailure(String message) {
				// Suppress
			}
    	});
        return new WGTest();
    }
	
	
	private void collectTestCases(String baseURI) throws Exception {
		
		InputStream is = new URI(baseURI).toURL().openStream();
		Model model = ModelFactory.createDefaultModel();
		try {
			model.read(is, baseURI, FileUtils.langTurtle);
		}
		catch(Exception ex) {
			System.err.println("ERROR: Cannot load " + baseURI + ": " + ex);
			return;
		}
		
		for(Resource manifest : model.listSubjectsWithProperty(RDF.type, MF.Manifest).toList()) {
			
			for(Statement includeS : manifest.listProperties(MF.include).toList()) {
				String include = includeS.getResource().getURI();
				collectTestCases(include);
			}
			
			for(Resource list : JenaUtil.getResourceProperties(manifest, MF.entries)) {
				for(RDFNode member : list.as(RDFList.class).asJavaList()) {
					if(!member.isLiteral()) {
						Resource test = (Resource) member;
						System.out.println("Processing test file " + test.getLocalName());
						if(test.hasProperty(RDF.type, SHT.MatchNodeShape)) {
							addTestIfSupported(new MatchNodeTestClass(test));
						}
						if(test.hasProperty(RDF.type, SHT.Validate)) {
							addTestIfSupported(new ValidateTestClass(test));
						}
						// TODO: Support other types
					}
				}
			}
		}
		
	}
	
	
	private void addTestIfSupported(AbstractSHACLTestClass test) {
		if(test.isSupported()) {
			addTest(test);
		}
	}
}
