package com.dspot.declex.wrapper.generate;

import java.io.IOException;
import java.nio.charset.Charset;

import org.androidannotations.AndroidAnnotationsEnvironment;
import org.androidannotations.internal.generation.CodeModelGenerator;
import org.androidannotations.internal.generation.ResourceCodeWriter;
import org.androidannotations.internal.generation.SourceCodeWriter;
import org.androidannotations.internal.process.ModelProcessor;

import com.helger.jcodemodel.writer.PrologCodeWriter;

public class DeclexCodeModelGenerator extends CodeModelGenerator {

	public DeclexCodeModelGenerator(String aaVersion, String encoding, AndroidAnnotationsEnvironment env) {
		super(env.getProcessingEnvironment().getFiler(), aaVersion, encoding);
		header = "DO NOT EDIT THIS FILE. Generated using DSpot Sp. z o.o - DecleX " + aaVersion + " "
				+ "and AndroidAnnotations .\n " + 
				"You can create a larger work that contains this file and distribute that work under terms of your choice.\n";
	}
	
}
