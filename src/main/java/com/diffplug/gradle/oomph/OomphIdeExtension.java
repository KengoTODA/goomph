/*
 * Copyright 2016 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.gradle.oomph;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.plugins.ide.eclipse.GenerateEclipseProject;

import com.diffplug.common.base.Errors;
import com.diffplug.common.base.Preconditions;
import com.diffplug.common.io.ByteSink;
import com.diffplug.common.io.ByteSource;
import com.diffplug.common.io.Files;
import com.diffplug.common.io.Resources;
import com.diffplug.common.swt.os.OS;
import com.diffplug.common.swt.os.SwtPlatform;
import com.diffplug.gradle.ConfigMisc;
import com.diffplug.gradle.FileMisc;
import com.diffplug.gradle.GoomphCacheLocations;
import com.diffplug.gradle.JavaExecable;
import com.diffplug.gradle.eclipserunner.EclipseIni;
import com.diffplug.gradle.p2.P2Model;

/** DSL for {@link OomphIdePlugin}. */
public class OomphIdeExtension {
	public static final String NAME = "oomphIde";

	private final Project project;
	private final WorkspaceRegistry workspaceRegistry;

	public OomphIdeExtension(Project project) throws IOException {
		this.project = Objects.requireNonNull(project);
		this.workspaceRegistry = WorkspaceRegistry.instance();
	}

	private final P2Model p2 = new P2Model();

	/** Returns the P2 model so that users can add the features they'd like. */
	public P2Model getP2() {
		return p2;
	}

	private Action<EclipseIni> eclipseIni;

	/** Sets properties in the `eclipse.ini`. */
	public void eclipseIni(Action<EclipseIni> eclipseIni) {
		Preconditions.checkArgument(this.eclipseIni == null, "Can only set eclipseIni once");
		this.eclipseIni = eclipseIni;
	}

	private String targetPlatformName = null;
	private Action<OomphTargetPlatform> targetPlatform = null;

	/** Sets the targetplatform configuration. */
	public void targetplatform(String name, Action<OomphTargetPlatform> targetPlatform) {
		Preconditions.checkArgument(this.targetPlatform == null, "Can only set targetplatform once");
		this.targetPlatformName = Objects.requireNonNull(name);
		this.targetPlatform = Objects.requireNonNull(targetPlatform);
	}

	private Object ideDir = "build/oomph-ide" + FileMisc.macApp();

	/** Sets the folder where the ide will be built. */
	public void ideDir(Object ideDir) {
		this.ideDir = ideDir;
	}

	/** Adds all eclipse projects from all the gradle projects. */
	public void addAllProjects() {
		Task setupIde = project.getTasks().getByName(OomphIdePlugin.IDE_SETUP);
		project.getRootProject().getAllprojects().forEach(p -> {
			if (p == project) {
				return;
			}
			// this project depends on all the others
			project.evaluationDependsOn(p.getPath());
			// and on all of their eclipse tasks
			p.getTasks().all(task -> {
				if ("eclipse".equals(task.getName())) {
					setupIde.dependsOn(task);
				}
				if (task instanceof GenerateEclipseProject) {
					addProjectFile(((GenerateEclipseProject) task).getOutputFile());
				}
			});
		});
	}

	private SortedSet<File> projectFiles = new TreeSet<>();

	/** Adds the given project file. */
	void addProjectFile(File projectFile) {
		Preconditions.checkArgument(projectFile.getName().equals(".project"), "Project file must be '.project', was %s", projectFile);
		projectFiles.add(projectFile);
	}

	private File getIdeDir() {
		return project.file(ideDir);
	}

	private File getWorkspaceDir() {
		return workspaceRegistry.workspaceDir(project, getIdeDir());
	}

	String state() {
		OomphTargetPlatform platformInstance = new OomphTargetPlatform(project);
		if (targetPlatform != null) {
			targetPlatform.execute(platformInstance);
		}
		return getIdeDir() + "\n" + platformInstance + "\n" + p2 + "\n" + projectFiles;
	}

	private Map<String, Supplier<byte[]>> pathToContent = new HashMap<>();

	/** Sets the given path within the ide directory to be a property file. */
	public void configProp(String file, Action<Map<String, String>> configSupplier) {
		pathToContent.put(file, ConfigMisc.props(configSupplier));
	}

	/** Sets the theme to be the classic eclipse look. */
	public void classicTheme() {
		configProp("workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.e4.ui.css.swt.theme.prefs", props -> {
			props.put("eclipse.preferences.version", "1");
			props.put("themeid", "org.eclipse.e4.ui.css.theme.e4_classic");
		});
	}

	/** Sets a nice font and whitespace settings. */
	public void niceText() {
		niceText(OS.getNative().winMacLinux("9.0", "11.0", "10.0"));
	}

	/** Sets a nice font and whitespace settings. */
	public void niceText(String fontSize) {
		// visible whitespace
		configProp("workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.ui.editors.prefs", props -> {
			props.put("eclipse.preferences.version", "1");
			props.put("showCarriageReturn", "false");
			props.put("showLineFeed", "false");
			props.put("showWhitespaceCharacters", "true");
		});
		// improved fonts
		configProp("workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.ui.workbench.prefs", props -> {
			props.put("eclipse.preferences.version", "1");
			String font = OS.getNative().winMacLinux("Consolas", "Monaco", "Monospace");
			props.put("org.eclipse.jface.textfont", "1|" + font + "|" + fontSize + "|0|WINDOWS|1|-12|0|0|0|400|0|0|0|0|3|2|1|49|" + font);
		});
	}

	static final String STALE_TOKEN = "token_stale";

	/** Returns true iff the installation is clean. */
	boolean isClean() {
		return Errors.rethrow().get(() -> FileMisc.hasToken(getIdeDir(), STALE_TOKEN, state()));
	}

	/** Sets up an IDE as described in this model from scratch. */
	void ideSetup() throws Exception {
		if (isClean()) {
			return;
		}
		File dir = getIdeDir();
		// else we've gotta set it up
		FileMisc.cleanDir(dir);
		FileMisc.cleanDir(getWorkspaceDir());
		// now we can install the IDE
		P2Model p2cached = p2.copy();
		p2cached.addArtifactRepoBundlePool();
		P2Model.DirectorApp app = p2cached.directorApp(dir, "OomphIde");
		app.consolelog();
		// share the install for quickness
		app.bundlepool(GoomphCacheLocations.bundlePool());
		// create the native launcher
		app.platform(SwtPlatform.getRunning());
		// create it
		app.runUsingBootstrapper(project);
		// set the application to use "${ide}/workspace"
		setInitialWorkspace();
		// setup the eclipse.ini file
		setupEclipseIni(dir);
		// setup any config files
		pathToContent.forEach((path, content) -> {
			File target = new File(dir, FileMisc.macContentsEclipse() + path);
			FileMisc.mkdirs(target.getParentFile());
			Errors.rethrow().run(() -> Files.write(content.get(), target));
		});
		// perform internal setup
		internalSetup(dir);
		// write out the splash image
		ByteSource source = Resources.asByteSource(OomphIdeExtension.class.getResource(SPLASH));
		ByteSink sink = Files.asByteSink(new File(dir, SPLASH));
		source.copyTo(sink);
		// write out a staleness token
		FileMisc.writeToken(dir, STALE_TOKEN, state());
	}

	static final String SPLASH = "splash.bmp";

	/** Sets the workspace directory. */
	private void setInitialWorkspace() throws IOException {
		File workspace = getWorkspaceDir();
		FileMisc.cleanDir(workspace);
		configProp("configuration/.settings/org.eclipse.ui.ide.prefs", map -> {
			map.put("eclipse.preferences.version", "1");
			map.put("MAX_RECENT_WORKSPACES", "5");
			map.put("RECENT_WORKSPACES", workspace.getAbsolutePath());
			map.put("RECENT_WORKSPACES_PROTOCOL", "3");
			map.put("SHOW_RECENT_WORKSPACES", "false");
			map.put("SHOW_WORKSPACE_SELECTION_DIALOG", "false");
		});
		// turn off quickstarts and tipsAndTricks
		configProp("workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.ui.ide.prefs", map -> {
			map.put("eclipse.preferences.version", "1");
			map.put("PROBLEMS_FILTERS_MIGRATE", "true");
			map.put("TASKS_FILTERS_MIGRATE", "true");
			map.put("platformState", Long.toString(System.currentTimeMillis() / 1000));
			map.put("quickStart", "false");
			map.put("tipsAndTricks", "false");
		});
	}

	/** Sets the eclipse.ini file. */
	private void setupEclipseIni(File ideDir) throws FileNotFoundException, IOException {
		File iniFile = new File(ideDir, FileMisc.macContentsEclipse() + "eclipse.ini");
		EclipseIni ini = EclipseIni.parseFrom(iniFile);
		ini.set("-data", getWorkspaceDir());
		// p2 director makes an invalid mac install out of the box.  Blech.
		if (OS.getNative().isMac()) {
			ini.set("-install", new File(ideDir, "Contents/MacOS"));
			ini.set("-configuration", new File(ideDir, "Contents/Eclipse/configuration"));
		}
		if (eclipseIni != null) {
			eclipseIni.execute(ini);
		}
		ini.writeTo(iniFile);
	}

	/** Performs setup actions with a running OSGi container. */
	private void internalSetup(File ideDir) throws IOException {
		project.getLogger().lifecycle("Internal setup");
		SetupWithinEclipse internal = new SetupWithinEclipse(ideDir);
		internal.add(new ProjectImporter(projectFiles));
		// setup the targetplatform
		if (targetPlatform != null) {
			OomphTargetPlatform targetPlatformInstance = new OomphTargetPlatform(project);
			targetPlatform.execute(targetPlatformInstance);
			internal.add(new TargetPlatformSetter(targetPlatformName, targetPlatformInstance.getInstallations()));
		}
		Errors.constrainTo(IOException.class).run(() -> JavaExecable.exec(project, internal));
	}

	/** Runs the IDE which was setup by {@link #ideSetup()}. */
	void ide() throws IOException {
		// clean any stale workspaces
		workspaceRegistry.clean();
		// then launch
		String launcher = OS.getNative().winMacLinux("eclipse.exe", "Contents/MacOS/eclipse", "eclipse");
		String[] args = new String[]{getIdeDir().getAbsolutePath() + "/" + launcher, "-showsplash", SPLASH};
		Runtime.getRuntime().exec(args, null, getIdeDir());
	}
}