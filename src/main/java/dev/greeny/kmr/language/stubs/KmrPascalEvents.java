package dev.greeny.kmr.language.stubs;

import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalFieldIdentifier;
import dev.greeny.kmr.language.psi.KmrPascalRecordType;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/** The events of a stub version, read from the (scope-invisible) Events stub. */
@Service(Service.Level.PROJECT)
public final class KmrPascalEvents
{

	private static final Key<CachedValue<List<KmrPascalEvent>>> KEY = Key.create("KmrPascalEvents");

	private final Project project;

	public KmrPascalEvents(@NotNull Project project)
	{
		this.project = project;
	}

	@NotNull
	public static KmrPascalEvents getInstance(@NotNull Project project)
	{
		return project.getService(KmrPascalEvents.class);
	}

	@NotNull
	public List<KmrPascalEvent> getEvents(@NotNull String version)
	{
		KmrPascalStubLibrary.StubSet set = KmrPascalStubLibrary.getInstance(project).getStubSet(version);
		PsiFile events = set.eventsPsiFile(project);
		if (events == null) {
			return Collections.emptyList();
		}
		// the provider must not capture PSI, so it re-fetches the file from the (PSI-free) stub set
		return CachedValuesManager.getManager(project).getCachedValue(events, KEY, () -> {
			PsiFile file = set.eventsPsiFile(project);
			return CachedValueProvider.Result.create(file == null ? Collections.<KmrPascalEvent>emptyList() : collect(file), PsiModificationTracker.MODIFICATION_COUNT);
		}, false);
	}

	/** Event whose conventional handler name matches (case-insensitive). */
	@Nullable
	public KmrPascalEvent findByHandlerName(@NotNull String version, @NotNull String name)
	{
		for (KmrPascalEvent event : getEvents(version)) {
			if (event.handlerName.equalsIgnoreCase(name)) {
				return event;
			}
		}
		return null;
	}

	/** Event whose {$EVENT} name matches (case-insensitive). */
	@Nullable
	public KmrPascalEvent findByEventName(@NotNull String version, @NotNull String name)
	{
		for (KmrPascalEvent event : getEvents(version)) {
			if (event.eventName.equalsIgnoreCase(name)) {
				return event;
			}
		}
		return null;
	}

	/**
	 * The event a routine handles: by its conventional name, or through a {@code {$EVENT evtX:Name}} directive
	 * anywhere in the routine's compilation unit; null when it is an ordinary routine.
	 */
	@Nullable
	public KmrPascalEvent findForRoutine(@NotNull KmrPascalRoutineDeclaration routine)
	{
		String name = routine.getName();
		PsiFile file = routine.getContainingFile();
		if (name == null || file == null) {
			return null;
		}
		String version = KmrPascalStubScope.getInstance(project).versionFor(file.getOriginalFile());
		KmrPascalEvent byName = findByHandlerName(version, name);
		if (byName != null) {
			return byName;
		}
		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(project).getUnitFor(file.getOriginalFile());
		for (KmrPascalCompilationUnit.Inclusion inclusion : unit.getInclusions()) {
			for (KmrPascalDirective directive : KmrPascalDirective.collect(inclusion.file)) {
				if (directive.kind == KmrPascalDirective.Kind.EVENT) {
					int colon = directive.argument.indexOf(':');
					if (colon >= 0 && directive.argument.substring(colon + 1).trim().equalsIgnoreCase(name)) {
						return findByEventName(version, directive.argument.substring(0, colon).trim());
					}
				}
			}
		}
		return null;
	}

	@NotNull
	private static List<KmrPascalEvent> collect(@NotNull PsiFile eventsFile)
	{
		KmrPascalRecordType record = PsiTreeUtil.findChildOfType(eventsFile, KmrPascalRecordType.class);
		if (record == null) {
			return Collections.emptyList();
		}
		return PsiTreeUtil.findChildrenOfType(record, KmrPascalFieldIdentifier.class).stream()
			.filter(field -> field.getName() != null && KmrPascalCallable.of(field) != null)
			.map(field -> {
				String handlerName = field.getName();
				KmrPascalDocComment doc = KmrPascalDocComment.of(field);
				String eventName = doc == null ? null : doc.getTagValue(KmrPascalDocComment.TAG_EVENT);
				if (eventName == null || eventName.isEmpty()) {
					// convention: OnHouseBuilt <-> evtHouseBuilt
					eventName = handlerName.regionMatches(true, 0, "On", 0, 2) ? "evt" + handlerName.substring(2) : handlerName;
				}
				return new KmrPascalEvent(handlerName, eventName, field, doc);
			})
			.toList();
	}

}
