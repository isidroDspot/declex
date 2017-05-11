/**
 * Copyright (C) 2016-2017 DSpot Sp. z o.o
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dspot.declex;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import org.androidannotations.internal.generation.CodeModelGenerator;
import org.androidannotations.internal.model.AnnotationElements;
import org.androidannotations.internal.model.AnnotationElements.AnnotatedAndRootElements;
import org.androidannotations.internal.model.AnnotationElementsHolder;
import org.androidannotations.internal.model.ModelExtractor;
import org.androidannotations.internal.process.ModelProcessor.ProcessResult;
import org.androidannotations.logger.Logger;
import org.androidannotations.logger.LoggerContext;
import org.androidannotations.logger.LoggerFactory;
import org.androidannotations.plugin.AndroidAnnotationsPlugin;

import com.dspot.declex.action.ActionHelper;
import com.dspot.declex.action.Actions;
import com.dspot.declex.generate.DeclexCodeModelGenerator;
import com.dspot.declex.helper.FilesCacheHelper;
import com.dspot.declex.helper.FilesCacheHelper.FileDetails;
import com.dspot.declex.util.DeclexConstant;
import com.dspot.declex.util.LayoutsParser;
import com.dspot.declex.util.MenuParser;
import com.dspot.declex.util.SharedRecords;
import com.dspot.declex.util.TypeUtils;
import com.dspot.declex.wrapper.RoundEnvironmentByCache;

public class DeclexProcessor extends org.androidannotations.internal.AndroidAnnotationProcessor {
	
	private static final boolean PRE_GENERATION_ENABLED = true;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(DeclexProcessor.class);
	
	protected LayoutsParser layoutsParser;
	protected MenuParser menuParser;
	protected Actions actions;
	
	protected FilesCacheHelper filesCacheHelper;
	protected Set<FileDetails> cachedFiles;
	protected int cachedFilesGenerated;
	
	@Override
	protected AndroidAnnotationsPlugin getCorePlugin() {
		return new DeclexCorePlugin();
	}
	
	@Override
	protected String getFramework() {
		return "DecleX";
	}
	
	@Override
	protected void helpersInitialization() {
		super.helpersInitialization();
		
		try {
			timeStats.start("Helpers Initialization");
			
			layoutsParser = new LayoutsParser(processingEnv, LOGGER);
			menuParser = new MenuParser(processingEnv, LOGGER);
			
			actions = new Actions(androidAnnotationsEnv);	
			
			filesCacheHelper = new FilesCacheHelper(androidAnnotationsEnv);
			cachedFiles = new HashSet<>();
			
			if (FilesCacheHelper.isCacheFilesEnabled()) {
				cachedFiles.addAll(filesCacheHelper.getAutogeneratedClasses()); //Write all Cached Autogenerated Classes
				cachedFilesGenerated = 0;
				
				if (PRE_GENERATION_ENABLED) {
					for (FileDetails details : cachedFiles) {
						if (!details.canBeUpdated) {
							details.preGenerate(androidAnnotationsEnv);
						}
					}
				}
				
			}
			
			timeStats.stop("Helpers Initialization");
			timeStats.logStats();
			
		} catch (Throwable e) {
			System.err.println("Something went wrong starting the framework");
			e.printStackTrace();
		}
	}
		
	@Override
	public boolean process(Set<? extends TypeElement> annotations,
			RoundEnvironment roundEnv) {
		
		LOGGER.info("Executing Declex");
				
		try {

			return super.process(annotations, roundEnv);
			
		} catch (Throwable e) {
			LOGGER.error("An error occured", e);
			LoggerContext.getInstance().close(true);
			
			e.printStackTrace();
			
			return false;
		}
		
	}
	
	@Override
	protected boolean nothingToDo(Set<? extends TypeElement> annotations,
			RoundEnvironment roundEnv) {		
		
		boolean nothingToDo = super.nothingToDo(annotations, roundEnv);
		
		if (nothingToDo) {			
			if (roundEnv.processingOver()) {
			
				timeStats.start("Writing Cache");
				
				long time = 0;
				//Wait till all the documents be saved
				while (FileDetails.isSaving()) {
					
					if (time > 30000) {
						LOGGER.error("Timeout writing to Cache for more than 30 segs");
						break;
					}
					
					try {
						Thread.sleep(100);
						time += 100;
					} catch (InterruptedException e) {};
				}
				
				if (FileDetails.getFailedGenerations().size() > 0) {
					LOGGER.error("Generation of Cached Files Failed with " + FileDetails.getFailedGenerations());
				}
				
				filesCacheHelper.saveGeneratedClasses();
				
				filesCacheHelper.ensureSources();
				
				timeStats.stop("Writing Cache");
			}
			
			return true;
		} else {
			//Update actions information in each round
			timeStats.start("Update Actions");
			actions.getActionsInformation();		
			timeStats.stop("Update Actions");
		}
		
		return false;
	}
	
	@Override
	protected AnnotationElementsHolder extractAnnotations(
			Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		
		if (!FilesCacheHelper.isCacheFilesEnabled())
			return super.extractAnnotations(annotations, roundEnv);
		
		timeStats.start("Extract Annotations");
		
		Map<TypeElement, Set<? extends Element>> annotatedElements = new HashMap<>();
		Set<TypeElement> noCachedAnnotations = new HashSet<>();
		
		Set<String> confirmedCachedClasses = new HashSet<>();
		Map<String, Boolean> confirmedAncestorsClasses = new HashMap<>();
		
		for (TypeElement annotation : annotations) {
			Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(annotation);
						
			Set<Element> annotatedElementsWithAnnotation = new HashSet<>();			
			
			annotationElements: for (Element element : elements) {
					
				final Element rootElement = TypeUtils.getRootElement(element);		
				final String rootElementClass = rootElement.asType().toString();
				
				if (confirmedAncestorsClasses.containsKey(rootElementClass)) {
					if (confirmedAncestorsClasses.get(rootElementClass)) {
						annotatedElementsWithAnnotation.add(element);
					}
					continue;
				}
				
				if (filesCacheHelper.isAncestor(rootElementClass)) {
										
					Set<String> subClasses = filesCacheHelper.getAncestorSubClasses(rootElementClass);
					for (String subClass : subClasses) {
						
						if (filesCacheHelper.isAncestor(subClass)) continue;
						
						//TODO
						//Only direct generated classes are checked for cache, but there's others generated classes
						//which are not checked, for instance the Events and Actions Holders, even if their cache
						//is invalid this mechanism doesn't guarantee to regenerate them
						final String generatedSubClassName = TypeUtils.getGeneratedClassName(subClass, androidAnnotationsEnv, false);
						
						if (!filesCacheHelper.hasCachedFile(generatedSubClassName)) {
							annotatedElementsWithAnnotation.add(element);
							
							confirmedAncestorsClasses.put(rootElementClass, true);
							continue annotationElements;
						}
					}
					
					confirmedAncestorsClasses.put(rootElementClass, false);
					continue;
				}
				
				//Get Generated Class Name for the rootElement				
				//TODO
				//Only direct generated classes are checked for cache, but there's others generated classes
				//which are not checked, for instance the Events and Actions Holders, even if their cache
				//is invalid this mechanism doesn't guarantee to regenerate them
				final String generatedClassName = TypeUtils.getGeneratedClassName(rootElement, androidAnnotationsEnv);
				if (confirmedCachedClasses.contains(generatedClassName)) continue;
				
				
				if (filesCacheHelper.hasCachedFile(generatedClassName)) {

					Set<FileDetails> detailsList = filesCacheHelper.getFileDetailsList(generatedClassName);
					for (FileDetails details : detailsList) {						
						cachedFiles.add(details);
						
						if (PRE_GENERATION_ENABLED && !details.canBeUpdated) {
							details.preGenerate(androidAnnotationsEnv);
						}
					}
					
					confirmedCachedClasses.add(generatedClassName);
					
				} else {
					annotatedElementsWithAnnotation.add(element);
				}
			}
			
			if (!annotatedElementsWithAnnotation.isEmpty()) {
				noCachedAnnotations.add(annotation);
				annotatedElements.put(annotation, annotatedElementsWithAnnotation);
			}
		}
		
		if (PRE_GENERATION_ENABLED) {
			filesCacheHelper.preGenerateSources();
		}
		
		ModelExtractor modelExtractor = new ModelExtractor();
		AnnotationElementsHolder extractedModel = modelExtractor.extract(
			noCachedAnnotations, 
			getSupportedAnnotationTypes(), 
			new RoundEnvironmentByCache(roundEnv, annotatedElements)
		);
				
		Set<AnnotatedAndRootElements> ancestorAnnotatedElements = extractedModel.getAllAncestors();
		for (AnnotatedAndRootElements elements : ancestorAnnotatedElements) {
			
			//Add ancestors to File Cache Service
			Element rootElement = elements.annotatedElement;
			if (rootElement.getEnclosingElement().getKind().equals(ElementKind.PACKAGE)) {
				FilesCacheHelper.getInstance()
				                .addAncestor(rootElement, elements.rootTypeElement);
			}
		}
		
		//Mark for generation the Action object if it is cached,
		//this will ensure that if the object is invalidated, it can be generated again
		try {
			FileDetails actionDetails = filesCacheHelper.getFileDetails(DeclexConstant.ACTION);
			if (cachedFiles.contains(actionDetails)) {
				Actions.getInstance().generateInRound = true;
			}
		} catch (Exception e) {
			//Action object hasn't be registered yet
		}
		
		timeStats.stop("Extract Annotations");
				
		return extractedModel;
	}
	
	@Override
	protected AnnotationElements validateAnnotations(
			AnnotationElements extractedModel,
			AnnotationElementsHolder validatingHolder) {
		
		filesCacheHelper.validateCurrentCache();
		
		AnnotationElements annotationElements = super.validateAnnotations(extractedModel, validatingHolder);
		
		//Run validations for Actions (it should be run after all the normal validations)
		timeStats.start("Validate Actions");
		LOGGER.info("Validating Actions");
		ActionHelper.getInstance(androidAnnotationsEnv).validate();
		timeStats.stop("Validate Actions");
		
		return annotationElements;
	}
	
	@Override
	protected ProcessResult processAnnotations(AnnotationElements validatedModel)
			throws Exception {
		
		ProcessResult result = super.processAnnotations(validatedModel);
		
		SharedRecords.priorityExecute();

		//Process Actions (it should be run after all the normal process)
		timeStats.start("Process Actions");
		LOGGER.info("Processing Actions");
		ActionHelper.getInstance(androidAnnotationsEnv).process();
		ActionHelper.getInstance(androidAnnotationsEnv).clear();
		timeStats.stop("Process Actions");
		
		return result;
	}
	
	@Override
	protected void generateSources(ProcessResult processResult)
			throws IOException {
				
		timeStats.start("Generate Sources");
		
		int numberOfFiles = processResult.codeModel.countArtifacts() + cachedFiles.size() - cachedFilesGenerated; 
				
		//Generate Actions
		if (!filesCacheHelper.hasCachedFile(DeclexConstant.ACTION) 
			|| !cachedFiles.contains(filesCacheHelper.getFileDetails(DeclexConstant.ACTION))) {
			LOGGER.debug("Generating Action Object");
			if (actions.buildActionsObject()) numberOfFiles++;			
		}
		
		LOGGER.info("Number of files generated by DecleX: {}", numberOfFiles);
		
		if (processResult.codeModel.countArtifacts() > 0) {
			CodeModelGenerator modelGenerator = new DeclexCodeModelGenerator(
				coreVersion, 
				androidAnnotationsEnv.getOptionValue(CodeModelGenerator.OPTION_ENCODING), 
				androidAnnotationsEnv
			);
			modelGenerator.generate(processResult);
		}
		
		for (FileDetails details : cachedFiles) {	
			if (!details.generated) {
				LOGGER.debug("Generating class from cache: {}", details.className);
				details.generate(androidAnnotationsEnv);
				cachedFilesGenerated++;
			}
		}
		
		timeStats.stop("Generate Sources");
		
		timeStats.start("Save Config");				
		SharedRecords.writeEvents(processingEnv);
		SharedRecords.writeDBModels(processingEnv);				
		timeStats.stop("Save Config");

	}
	
	public static void main(String[] args) {
		System.out.println("DecleX Service");
		
		int i = 0;
		while (i < args.length) {
			
			switch (args[i]) {
			
			case "generate":
				try {
					System.setOut(outputFile(FilesCacheHelper.getExternalCache().getAbsolutePath() + File.separator + "generate.log"));
					
					System.out.println("Running Generate Cache Service");
					FilesCacheHelper.runGenerateSources(5);
				} catch (Throwable e) {
					e.printStackTrace();
					System.exit(1);
				} finally {
					System.out.close();
					System.exit(0);
				}
				break;
			
			case "cache":
				try {
					System.setOut(outputFile(FilesCacheHelper.getExternalCache().getAbsolutePath() + File.separator + "cache.log"));
				
					System.out.println("Running Cache Service");
					FilesCacheHelper.runClassCacheCreation();
				} catch (Throwable e) {
					e.printStackTrace();
					System.exit(1);
				} finally {
					System.out.close();
					System.exit(0);
				}
				break;

			default:
				System.out.println("Unknow argument: " + args[i]);
				break;
			}
			
			i++;
		}
	}
	
	private static PrintStream outputFile(String name) throws FileNotFoundException {
		File file = new File(name);
		if (file.exists()) file.delete();
        return new PrintStream(new BufferedOutputStream(new FileOutputStream(name)));
    }
}
