package com.comodide.patterns;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.entity.EntityCreationPreferences;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.AnonymousIndividualProperties;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.comodide.axiomatization.OplaAnnotationManager;
import com.comodide.configuration.ComodideConfiguration;
import com.comodide.configuration.Namespaces;

/**
 * Class that, based on a selected pattern, user preferences, and (possibly) the
 * target ontology, creates an ontology design pattern instantiation, in the
 * form of both a) the logic axioms representing the design solution encoded in
 * the pattern itself, and b) OPLa metadata on that solution describing the use
 * of a pattern-based module.
 * 
 * @author Karl Hammar <karl@karlhammar.com>
 *
 */
public class PatternInstantiator
{
	/** Bookkeeping */
	private static final Logger log = LoggerFactory.getLogger(PatternInstantiator.class);

	private OWLOntology           pattern;
	private final Boolean         useTargetNamespace;
	private final String          patternLabel;
	private final IRI             createdModuleIRI;
	private final OWLOntology     targetOntology;
	private final OWLEntityFinder entityFinder;
	private final IRI             targetOntologyIri;
	private final String          entitySeparator;

	public PatternInstantiator(OWLOntology pattern, String patternLabel, OWLModelManager modelManager)
	{
		// JFrame
		super();
		// Assign private fields
		this.pattern = pattern;
		this.patternLabel = patternLabel;
		this.useTargetNamespace = ComodideConfiguration.getUseTargetNamespace();
		this.targetOntology = modelManager.getActiveOntology();
		this.targetOntologyIri = this.targetOntology.getOntologyID().getOntologyIRI().or(IRI.generateDocumentIRI());
		this.entityFinder = modelManager.getOWLEntityFinder();
		this.entitySeparator = EntityCreationPreferences.getDefaultSeparator();
		// Create the name of the module
//		String moduleName = String.format("-modules/%s", UUID.randomUUID().toString());
//		this.createdModuleIri = IRI.create(targetOntologyIri.toString(), moduleName);
		String moduleName = patternLabel + " Module";
		moduleName = moduleName.replace(" ", "_");
		this.createdModuleIRI = IRI.create(targetOntologyIri.toString() + "#" + moduleName);
	}

	/**
	 * @return Axioms representing the instantiation of a pattern into a target
	 *         ontology (depending on user configuration in the
	 *         {@link PatternInstantiationConfiguration} class, either uses the
	 *         pattern namespace for classes, properties, etc., or clones the design
	 *         in the target namespace).
	 */
	public Set<OWLAxiom> getInstantiationAxioms()
	{
		if (useTargetNamespace)
		{
			// The below configuration, and corresponding reset to that configuration
			// at the end of the if block, is a workaround for an OWLAPI bug;
			// see https://github.com/owlcs/owlapi/issues/892
			AnonymousIndividualProperties.setRemapAllAnonymousIndividualsIds(false);

			OWLOntologyManager patternManager = pattern.getOWLOntologyManager();
			OWLEntityRenamer   renamer        = new OWLEntityRenamer(patternManager, Collections.singleton(pattern));
			for (OWLEntity entity : pattern.getSignature())
			{
				if (!entity.isBuiltIn() && !entity.getIRI().toString().contains(Namespaces.OPLA_CORE)
						&& !entity.getIRI().toString().contains(Namespaces.OPLA_SD))
				{

					String entityShortName = entity.getIRI().getShortForm();
					// If the entity is a property, ensure that it does not clash with a property
					// that is already defined in the target ontology; CoModIDE only allows for
					// simple
					// single-domain and single-range properties, if an entity is inserted that
					// already
					// exists, this paradigm may be broken. Thus rename if needed. /Karl, October 7,
					// 2019
					if (entity.isOWLObjectProperty() || entity.isOWLDataProperty())
					{
						while (!entityFinder.getMatchingOWLEntities(entityShortName).isEmpty())
						{
							entityShortName = entityShortName + "-1";
						}
					}
					String                  entityShortNameWithSeparator = this.entitySeparator + entityShortName;
					IRI                     newIRI                       = IRI.create(targetOntologyIri.toString(),
							entityShortNameWithSeparator);
					List<OWLOntologyChange> changes                      = renamer.changeIRI(entity, newIRI);
					patternManager.applyChanges(changes);
				}
			}
			AnonymousIndividualProperties.resetToDefault();
		}
		return pattern.getAxioms();
	}

	/**
	 * 
	 * @return Axioms representing metadata on the pattern instantiation, e.g., that
	 *         the instantiation is an opla:Module, which in has a link via turn
	 *         opla:reusesPatternAsTemplate to some opla:Pattern, and that all
	 *         instantiated entities are linked to the module via opla:isNativeTo.
	 */
	public Set<OWLAxiom> getModuleAnnotationAxioms()
	{
		// 1. Create temporary manager, factory, and return value holder
		OWLOntologyManager manager                = OWLManager.createOWLOntologyManager();
		OWLDataFactory     factory                = manager.getOWLDataFactory();
		Set<OWLAxiom>      moduleAnnotationAxioms = new HashSet<OWLAxiom>();

		// 2. Get pattern IRI
		IRI patternIRI = pattern.getOntologyID().getOntologyIRI().or(IRI.generateDocumentIRI());

		// 3. Define that <patternIRI> rdf:type opla:pattern; rdfs:label PatternLabel
		OWLClass               oplaPatternClass   = factory
				.getOWLClass(IRI.create(String.format("%sPattern", Namespaces.OPLA_CORE)));
		OWLNamedIndividual     patternIndividual  = factory.getOWLNamedIndividual(patternIRI);
		OWLClassAssertionAxiom patternTypingAxiom = factory.getOWLClassAssertionAxiom(oplaPatternClass,
				patternIndividual);
		moduleAnnotationAxioms.add(patternTypingAxiom);
		OWLAnnotationAssertionAxiom patternLabelAxiom = factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
				patternIRI, factory.getOWLLiteral(patternLabel));
		moduleAnnotationAxioms.add(patternLabelAxiom);

		// 4 define that <moduleIri> rdf:type opla:Module; rdfs:label
		// SomethingReasonable
		OWLNamedIndividual     moduleIndividual  = factory.getOWLNamedIndividual(createdModuleIRI);
		OWLClassAssertionAxiom moduleTypingAxiom = factory.getOWLClassAssertionAxiom(OplaAnnotationManager.module,
				moduleIndividual);
		moduleAnnotationAxioms.add(moduleTypingAxiom);
//		OWLAnnotationAssertionAxiom moduleLabelAxiom = factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
//				createdModuleIRI, factory.getOWLLiteral(String.format("'%s' ODP Instantiation Module", patternLabel)));
//		moduleAnnotationAxioms.add(moduleLabelAxiom);

		// 5 define that the module reusesPatternAsTemplate thePattern
		OWLAnnotationProperty       reusesPatternAsTemplate = factory
				.getOWLAnnotationProperty(IRI.create(String.format("%sreusesPatternAsTemplate", Namespaces.OPLA_CORE)));
		OWLAnnotationAssertionAxiom patternReuseAxiom       = factory
				.getOWLAnnotationAssertionAxiom(reusesPatternAsTemplate, createdModuleIRI, patternIRI);
		moduleAnnotationAxioms.add(patternReuseAxiom);

		// 6. For each entity in instantiated pattern module: annotate that it
		// opla:isNativeTo <moduleIRI>
		for (OWLEntity entity : pattern.getSignature())
		{
			if (!entity.isBuiltIn() && !entity.getIRI().toString().contains(Namespaces.OPLA_CORE))
			{
				OWLAnnotationAssertionAxiom entityNativeToAxiom = factory.getOWLAnnotationAssertionAxiom(
						OplaAnnotationManager.isNativeTo, entity.getIRI(), createdModuleIRI);
				moduleAnnotationAxioms.add(entityNativeToAxiom);
			}
		}
		
		return moduleAnnotationAxioms;
	}
}
