package dev.greeny.kmr.language.editor;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.util.IncorrectOperationException;
import dev.greeny.kmr.language.psi.KmrPascalElementFactory;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code {$EVENT evtHouseBuilt:MyHandler}}: the left half refers to an event of the target version's Events stub,
 * the right half to a procedure of the compilation unit.
 */
public class KmrPascalEventDirectiveReference extends PsiReferenceBase<PsiComment>
{

	private final boolean handlerPart;
	private final String name;

	private KmrPascalEventDirectiveReference(@NotNull PsiComment element, @NotNull TextRange range, @NotNull String name, boolean handlerPart)
	{
		super(element, range, true);
		this.name = name;
		this.handlerPart = handlerPart;
	}

	@NotNull
	public static PsiReference[] create(@NotNull PsiComment element, @NotNull KmrPascalDirective directive)
	{
		TextRange argumentRange = directive.argumentRange;
		if (argumentRange == null) {
			return PsiReference.EMPTY_ARRAY;
		}
		String argument = directive.argument;
		int colon = argument.indexOf(':');
		if (colon < 0) {
			return new PsiReference[]{new KmrPascalEventDirectiveReference(element, argumentRange, argument.trim(), false)};
		}
		String eventName = argument.substring(0, colon).trim();
		String handlerName = argument.substring(colon + 1).trim();
		int start = argumentRange.getStartOffset();
		TextRange eventRange = TextRange.create(start, start + colon);
		TextRange handlerRange = TextRange.create(start + colon + 1, argumentRange.getEndOffset());
		return new PsiReference[]{
			new KmrPascalEventDirectiveReference(element, trim(eventRange, element.getText()), eventName, false),
			new KmrPascalEventDirectiveReference(element, trim(handlerRange, element.getText()), handlerName, true),
		};
	}

	public boolean isHandlerPart()
	{
		return handlerPart;
	}

	/** The event or procedure name this half of the directive refers to. */
	@NotNull
	public String getName()
	{
		return name;
	}

	@Override
	public @Nullable PsiElement resolve()
	{
		if (name.isEmpty()) {
			return null;
		}
		PsiFile file = myElement.getContainingFile();
		if (handlerPart) {
			for (KmrPascalNamedElement global : KmrPascalResolveContextService.getInstance(myElement.getProject()).getUnitFor(file).findGlobals(name)) {
				if (global instanceof KmrPascalRoutineDeclaration) {
					return global;
				}
			}
			return null;
		}
		String version = KmrPascalStubScope.getInstance(myElement.getProject()).versionFor(file);
		KmrPascalEvent event = KmrPascalEvents.getInstance(myElement.getProject()).findByEventName(version, name);
		return event == null ? null : event.field;
	}

	@Override
	public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException
	{
		String text = myElement.getText();
		TextRange range = getRangeInElement();
		String newText = text.substring(0, range.getStartOffset()) + newElementName + text.substring(range.getEndOffset());
		return myElement.replace(KmrPascalElementFactory.createFile(myElement.getProject(), newText).getFirstChild());
	}

	@Override
	public Object @NotNull [] getVariants()
	{
		return EMPTY_ARRAY;
	}

	/** Shrinks a range so it excludes surrounding whitespace in the given text. */
	private static TextRange trim(@NotNull TextRange range, @NotNull String text)
	{
		int start = range.getStartOffset();
		int end = range.getEndOffset();
		while (start < end && Character.isWhitespace(text.charAt(start))) start++;
		while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
		return TextRange.create(start, end);
	}

}
