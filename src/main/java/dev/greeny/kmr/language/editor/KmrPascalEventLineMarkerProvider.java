package dev.greeny.kmr.language.editor;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import dev.greeny.kmr.language.KmrPascalIcons;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;

/**
 * Gutter icon on procedures the game calls as event handlers and on {@code {$EVENT evtX:Handler}} directives,
 * navigating to the event's description in the stubs (and, for directives, to the handler).
 * <p>
 * Implemented as a fast-pass {@link com.intellij.codeInsight.daemon.LineMarkerProvider} (not a "related items"
 * provider): the icons appear together with the highlighting instead of after the slow line marker pass.
 */
public class KmrPascalEventLineMarkerProvider extends LineMarkerProviderDescriptor
{

	@Override
	public String getName()
	{
		return "Game event handlers";
	}

	@Override
	public @Nullable Icon getIcon()
	{
		return KmrPascalIcons.EVENT_HANDLER;
	}

	@Override
	public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element)
	{
		PsiFile file = element.getContainingFile();
		if (file == null || KmrPascalStubLibrary.isStubFile(file)) {
			return null;
		}
		if (element.getNode().getElementType() == KmrPascalTypes.IDENTIFIER && element.getParent() instanceof KmrPascalRoutineDeclaration) {
			return routineMarker(element, (KmrPascalRoutineDeclaration) element.getParent());
		}
		if (element instanceof PsiComment && element.getNode().getElementType() == KmrPascalTypes.DIRECTIVE_EVENT) {
			return directiveMarker((PsiComment) element);
		}
		return null;
	}

	@Nullable
	private static LineMarkerInfo<?> routineMarker(@NotNull PsiElement identifier, @NotNull KmrPascalRoutineDeclaration routine)
	{
		if (routine.getNameIdentifier() != identifier) {
			return null;
		}
		KmrPascalEvent event = KmrPascalEvents.getInstance(routine.getProject()).findForRoutine(routine);
		if (event == null) {
			return null;
		}
		return NavigationGutterIconBuilder.create(KmrPascalIcons.EVENT_HANDLER)
			.setTarget(event.field)
			.setTooltipText("Game event handler for " + event.eventName + description(event))
			.createLineMarkerInfo(identifier);
	}

	@Nullable
	private static LineMarkerInfo<?> directiveMarker(@NotNull PsiComment comment)
	{
		KmrPascalDirective directive = KmrPascalDirective.of(comment);
		int colon = directive == null ? -1 : directive.argument.indexOf(':');
		if (colon < 0) {
			return null;
		}
		String eventName = directive.argument.substring(0, colon).trim();
		String handlerName = directive.argument.substring(colon + 1).trim();
		PsiFile file = comment.getContainingFile();
		String version = KmrPascalStubScope.getInstance(comment.getProject()).versionFor(file.getOriginalFile());
		KmrPascalEvent event = KmrPascalEvents.getInstance(comment.getProject()).findByEventName(version, eventName);
		if (event == null) {
			return null; // the preprocessor inspection reports the unknown event
		}
		List<PsiElement> targets = new ArrayList<>();
		targets.add(event.field);
		for (PsiReference reference : comment.getReferences()) {
			if (reference instanceof KmrPascalEventDirectiveReference && ((KmrPascalEventDirectiveReference) reference).isHandlerPart()) {
				PsiElement handler = reference.resolve();
				if (handler != null) {
					targets.add(handler);
				}
			}
		}
		return NavigationGutterIconBuilder.create(KmrPascalIcons.EVENT_HANDLER)
			.setTargets(targets)
			.setPopupTitle("Event " + event.eventName)
			.setTooltipText("Game event handler registration: " + handlerName + " handles " + event.eventName + description(event))
			.createLineMarkerInfo(comment);
	}

	@NotNull
	private static String description(@NotNull KmrPascalEvent event)
	{
		if (event.doc == null || event.doc.getDescription().isEmpty()) {
			return "";
		}
		String text = event.doc.getDescription();
		int newline = text.indexOf('\n');
		return ": " + (newline < 0 ? text : text.substring(0, newline));
	}

}
