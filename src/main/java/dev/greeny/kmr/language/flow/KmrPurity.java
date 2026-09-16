package dev.greeny.kmr.language.flow;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * What a call does to the world the flow analysis tracks. A script runs to completion inside one game tick (an event
 * fires, the handler runs, nothing else happens in between), so the game state only changes through {@code Actions}:
 * every {@code States} query returns the same value until an {@code Actions} member is called. {@code Utils} and the
 * standard routines are pure functions of their arguments. A routine of the script itself may do anything: change
 * global variables and call {@code Actions}.
 */
public final class KmrPurity
{

	/** Classification of a resolved callee. */
	public enum Kind
	{
		/** A {@code States} function: deterministic until the next {@code Actions} call. */
		GAME_QUERY,
		/** {@code Utils} or a standard routine: a pure function of its arguments. */
		PURE,
		/** An {@code Actions} member that only shows or plays something: the game state stays as it is. */
		PRESENTATION,
		/** An {@code Actions} member that changes the game. */
		GAME_CHANGE,
		/** A routine of the script: may change globals and the game. */
		SCRIPT_ROUTINE,
		/** Unresolved, random, or otherwise unknown: assume the worst. */
		UNKNOWN
	}

	/** Actions members that neither the game state nor any script variable depends on. */
	private static final Set<String> PRESENTATION_PREFIXES = Set.of("showmsg", "overlaytext", "log", "cinematic", "play", "stoploopedwav", "stoploopedogg", "stopsound");

	private KmrPurity()
	{
	}

	@NotNull
	public static Kind of(@Nullable PsiElement callee)
	{
		if (callee == null) {
			return Kind.UNKNOWN;
		}
		if (callee instanceof KmrPascalRoutineDeclaration) {
			return Kind.SCRIPT_ROUTINE;
		}
		if (!(callee instanceof KmrPascalNamedElement)) {
			return Kind.UNKNOWN;
		}
		PsiFile file = callee.getContainingFile();
		String name = String.valueOf(((KmrPascalNamedElement) callee).getName()).toLowerCase(Locale.ROOT);
		if (KmrPascalStubLibrary.isSystemStubFile(file)) {
			return Kind.PURE;
		}
		if (!KmrPascalStubLibrary.isStubFile(file) || !(callee instanceof KmrPascalFieldIdentifier)) {
			// a procedural-type variable of the script: could be anything
			return Kind.UNKNOWN;
		}
		String owner = ownerRecordName((KmrPascalFieldIdentifier) callee);
		if (name.contains("random")) {
			return Kind.UNKNOWN;
		}
		switch (owner) {
			case "tkmscriptstates":
				return Kind.GAME_QUERY;
			case "tkmscriptutils":
				return Kind.PURE;
			case "tkmscriptactions":
				for (String prefix : PRESENTATION_PREFIXES) {
					if (name.startsWith(prefix)) {
						return Kind.PRESENTATION;
					}
				}
				return Kind.GAME_CHANGE;
			default:
				return Kind.UNKNOWN;
		}
	}

	/** Whether the callee's result is a stable function of its arguments (and, for game queries, of the game state). */
	public static boolean isDeterministicFunction(@Nullable PsiElement callee)
	{
		Kind kind = of(callee);
		if (kind != Kind.GAME_QUERY && kind != Kind.PURE) {
			return false;
		}
		KmrPascalCallable callable = KmrPascalCallable.of(callee);
		return callable != null && callable.isFunction() && !hasVarParameters(callable);
	}

	/** A function writing to {@code var}/{@code out} parameters is not a value; also its arguments are changed by it. */
	public static boolean hasVarParameters(@NotNull KmrPascalCallable callable)
	{
		for (KmrPascalParameterDeclaration parameter : callable.getParameterDeclarations()) {
			if (isByReference(parameter)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isByReference(@NotNull KmrPascalParameterDeclaration parameter)
	{
		KmrPascalArgumentModifier modifier = parameter.getArgumentModifier();
		if (modifier == null) {
			return false;
		}
		String text = modifier.getText().toLowerCase(Locale.ROOT);
		return text.equals("var") || text.equals("out");
	}

	@NotNull
	private static String ownerRecordName(@NotNull KmrPascalFieldIdentifier field)
	{
		KmrPascalTypeDeclaration record = PsiTreeUtil.getParentOfType(field, KmrPascalTypeDeclaration.class);
		return record == null || record.getName() == null ? "" : record.getName().toLowerCase(Locale.ROOT);
	}

}
