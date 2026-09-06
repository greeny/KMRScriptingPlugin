package dev.greeny.kmr.language.unit;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project-level settings: manually marked entry points, the resolve context chosen for shared files, and which
 * API stub set (game version) is used, per entry point with a project default.
 */
@State(name = "KmrPascalProjectSettings", storages = @Storage("kmrPascal.xml"))
@Service(Service.Level.PROJECT)
public final class KmrPascalProjectSettings implements PersistentStateComponent<KmrPascalProjectSettings.State>
{

	public static class State
	{
		/** URLs of scripts the user marked as entry points (in addition to auto-detected ones). */
		public List<String> entryPoints = new ArrayList<>();
		/** file URL -> entry point URL chosen as resolve context. */
		public Map<String, String> resolveContexts = new HashMap<>();
		/** Stub set (game version) used unless an entry point overrides it. */
		public String defaultStubVersion = KmrPascalStubLibrary.LATEST;
		/** entry point URL -> stub set, for maps targeting another game version than the default. */
		public Map<String, String> entryPointStubVersions = new HashMap<>();
	}

	private State state = new State();

	@NotNull
	public static KmrPascalProjectSettings getInstance(@NotNull Project project)
	{
		return project.getService(KmrPascalProjectSettings.class);
	}

	@Override
	public @NotNull State getState()
	{
		return state;
	}

	@Override
	public void loadState(@NotNull State state)
	{
		this.state = state;
	}

	public boolean isManualEntryPoint(@NotNull VirtualFile file)
	{
		return state.entryPoints.contains(file.getUrl());
	}

	public void setManualEntryPoint(@NotNull VirtualFile file, boolean entryPoint)
	{
		if (entryPoint) {
			if (!state.entryPoints.contains(file.getUrl())) {
				state.entryPoints.add(file.getUrl());
			}
		} else {
			state.entryPoints.remove(file.getUrl());
		}
	}

	@Nullable
	public String getResolveContextUrl(@NotNull VirtualFile file)
	{
		return state.resolveContexts.get(file.getUrl());
	}

	public void setResolveContext(@NotNull VirtualFile file, @Nullable VirtualFile entryPoint)
	{
		if (entryPoint == null) {
			state.resolveContexts.remove(file.getUrl());
		} else {
			state.resolveContexts.put(file.getUrl(), entryPoint.getUrl());
		}
	}

	@NotNull
	public String getDefaultStubVersion()
	{
		return KmrPascalStubLibrary.normalize(state.defaultStubVersion);
	}

	public void setDefaultStubVersion(@NotNull String version)
	{
		state.defaultStubVersion = version;
	}

	/** The stub set for code analysed in the context of the given entry point (null = no file, use default). */
	@NotNull
	public String getStubVersionFor(@Nullable VirtualFile entryPoint)
	{
		String override = entryPoint == null ? null : state.entryPointStubVersions.get(entryPoint.getUrl());
		return override == null ? getDefaultStubVersion() : KmrPascalStubLibrary.normalize(override);
	}

	/** Per-entry-point override; null removes it. */
	@Nullable
	public String getStubVersionOverride(@NotNull VirtualFile entryPoint)
	{
		return state.entryPointStubVersions.get(entryPoint.getUrl());
	}

	public void setStubVersionOverride(@NotNull VirtualFile entryPoint, @Nullable String version)
	{
		if (version == null) {
			state.entryPointStubVersions.remove(entryPoint.getUrl());
		} else {
			state.entryPointStubVersions.put(entryPoint.getUrl(), version);
		}
	}

}
