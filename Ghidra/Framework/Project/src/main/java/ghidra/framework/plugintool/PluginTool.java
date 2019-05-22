/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.framework.plugintool;

import static ghidra.framework.model.ToolTemplate.*;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

import org.jdom.Element;

import docking.*;
import docking.action.*;
import docking.actions.DockingToolActionManager;
import docking.framework.AboutDialog;
import docking.framework.ApplicationInformationDisplayFactory;
import docking.framework.SplashScreen;
import docking.help.Help;
import docking.help.HelpService;
import docking.tool.util.DockingToolConstants;
import docking.widgets.OptionDialog;
import ghidra.framework.OperatingSystem;
import ghidra.framework.Platform;
import ghidra.framework.cmd.BackgroundCommand;
import ghidra.framework.cmd.Command;
import ghidra.framework.main.AppInfo;
import ghidra.framework.main.UserAgreementDialog;
import ghidra.framework.model.*;
import ghidra.framework.options.*;
import ghidra.framework.plugintool.dialog.ExtensionTableProvider;
import ghidra.framework.plugintool.dialog.ManagePluginsDialog;
import ghidra.framework.plugintool.mgr.*;
import ghidra.framework.plugintool.util.*;
import ghidra.framework.project.ProjectDataService;
import ghidra.framework.project.tool.ToolIconURL;
import ghidra.util.*;
import ghidra.util.datastruct.WeakDataStructureFactory;
import ghidra.util.datastruct.WeakSet;
import ghidra.util.task.Task;
import ghidra.util.task.TaskLauncher;

/**
 * Base class that is a container to manage plugins and their actions, and
 * to coordinate the firing of plugin events and tool events. A
 * PluginTool may have visible components supplied by
 * <pre>ComponentProviders </pre>. These components may be docked within the
 * tool, or moved out into their own windows.
 * <p>The PluginTool also manages tasks that run in the background, and
 * options used by the plugins.
 * </p>
 *
 */
public abstract class PluginTool extends AbstractDockingTool
		implements Tool, DockWinListener, ServiceProvider {

	private static final String DOCKING_WINDOWS_ON_TOP = "Docking Windows On Top";

	private static final String SAVE_DIALOG_TITLE = "Save Tool - Possible Conflict";

	private ProjectManager projectManager;
	private Project project;
	private String instanceName = "";
	protected String toolName;
	private String fullName;
	private String subTitle;

	private ServiceManager serviceMgr;
	private ToolTaskManager taskMgr;
	private OptionsManager optionsMgr;
	private PluginManager pluginMgr;
	private EventManager eventMgr;
	private DialogManager dialogMgr;
	private PropertyChangeSupport propertyChangeMgr;

	private WeakSet<PopupListener> popupListeners =
		WeakDataStructureFactory.createSingleThreadAccessWeakSet();
	private OptionsChangeListener optionsListener = new ToolOptionsListener();
	protected ManagePluginsDialog manageDialog;
	protected ExtensionTableProvider extensionTableProvider;

	protected ToolIconURL iconURL = new ToolIconURL("view_detailed.png");

	private DockingAction exportToolAction;
	private ToolServices toolServices;

	private boolean isConfigurable = true;
	protected boolean isDisposed = false;

	/**
	 * Construct a new PluginTool.
	 *
	 * @param project project that contains this tool
	 * @param template the template from which to load this tool
	 */
	public PluginTool(Project project, ToolTemplate template) {
		this(project, project.getProjectManager(), project.getToolServices(), template.getName(),
			true, true, false);

		setIconURL(template.getIconURL());

		boolean hasErrors = restoreFromXml(template.getToolElement());
		if (!hasErrors) {
			configChangedFlag = false;
		}
		optionsMgr.validateOptions();
	}

	/**
	 * Construct a new PluginTool.
	 *
	 * @param project project that contains the tool
	 * @param name the name of the tool
	 * @param isDockable true if the tool contains components that can be docked
	 * @param hasStatus true if the tool should display a status component
	 * @param isModal true if the tool is modal, meaning that while this tool is visible,
	 *        no other tool or dialog in Ghidra can have focus
	 */
	public PluginTool(Project project, String name, boolean isDockable, boolean hasStatus,
			boolean isModal) {
		this(project, project.getProjectManager(), project.getToolServices(), name, isDockable,
			hasStatus, isModal);
	}

	public PluginTool(Project project, ProjectManager projectManager, ToolServices toolServices,
			String name, boolean isDockable, boolean hasStatus, boolean isModal) {
		this.project = project;
		this.projectManager = projectManager;
		this.toolServices = toolServices;
		propertyChangeMgr = new PropertyChangeSupport(this);
		winMgr = createDockingWindowManager(isDockable, hasStatus, isModal);
		taskMgr = new ToolTaskManager(this);
		optionsMgr = new OptionsManager(this);
		setToolOptionsHelpLocation();
		winMgr.addStatusItem(taskMgr.getMonitorComponent(), false, true);
		winMgr.removeStatusItem(taskMgr.getMonitorComponent());
		eventMgr = new EventManager(this);
		serviceMgr = new ServiceManager();
		installServices();
		actionMgr = new DockingToolActionManager(this, winMgr);
		pluginMgr = new PluginManager(this, serviceMgr);
		dialogMgr = new DialogManager(this);
		initActions();
		initOptions();

		setToolName(name);

		PluginToolMacQuitHandler.install(this);
		PluginToolMacAboutHandler.install(winMgr);

		installHomeButton();
	}

	public abstract PluginClassManager getPluginClassManager();

	/**
	 * This method exists here, as opposed to inline in the constructor, so that subclasses can
	 * alter the behavior of the DockingWindowManager construction.
	 *
	 * @param isDockable true if the tool contains components that can be
	 * docked
	 * @param hasStatus true if the tool should display a status component
	 * @param isModal true if the tool is modal, meaning that while this tool
	 * is visible, no other tool or dialog in Ghidra can have focus
	 * @return a new DockingWindowManager
	 */
	protected DockingWindowManager createDockingWindowManager(boolean isDockable, boolean hasStatus,
			boolean isModal) {

		List<Image> windowIcons = ApplicationInformationDisplayFactory.getWindowIcons();
		DockingWindowManager newManager = new DockingWindowManager("EMPTY", windowIcons, this,
			isModal, isDockable, hasStatus, null);
		return newManager;
	}

	protected void installHomeButton() {

		ImageIcon homeIcon = ApplicationInformationDisplayFactory.getHomeIcon();
		if (homeIcon == null) {
			Msg.debug(this,
				"If you would like a button to show the Front End, then set the home icon");
			return;
		}

		Runnable callback = ApplicationInformationDisplayFactory.getHomeCallback();
		winMgr.setHomeButton(homeIcon, callback);
	}

	/**
	 * Placeholder for subclasses to get a chance to install actions before plugins.
	 *
	 */
	protected void initActions() {
		// placeholder
	}

	@Override
	public DockingWindowManager getWindowManager() {
		return winMgr;
	}

	private void setDefaultOptionValues() {
		Options toolOptions = optionsMgr.getOptions("Tool");
		boolean windowsOnTop = toolOptions.getBoolean(DOCKING_WINDOWS_ON_TOP, false);
		winMgr.setWindowsOnTop(windowsOnTop);
	}

	private void initOptions() {
		ToolOptions toolOptions = optionsMgr.getOptions("Tool");
		toolOptions.registerOption(DOCKING_WINDOWS_ON_TOP, false, null,
			"Determines whether a docked window will always be shown on " +
				"top of its parent window.");

		// we must call this before the init work below to make sure that the options object
		// we use has been created
		setDefaultOptionValues();

		toolOptions.addOptionsChangeListener(optionsListener);

		serviceMgr.addService(OptionsService.class, optionsMgr);
	}

	protected void optionsChanged(Options options, String name, Object oldValue, Object newValue) {
		if (name.equals(DOCKING_WINDOWS_ON_TOP)) {
			winMgr.setWindowsOnTop(((Boolean) newValue).booleanValue());
		}
	}

	/**
	 * Set the Tool option (GhidraOptions.OPTION_DOCKING_WINDOWS_ON_TOP)
	 * for whether a docked window will always be shown on top of its parent window.
	 * @param b true means that the docked window will always appear on top of its
	 * parent window; false means to allow the docked window to be "hidden" under its
	 * parent dialog
	 */
	public void setWindowsOnTop(boolean b) {
		winMgr.setWindowsOnTop(b);
	}

	/**
	 * Return the value of the Tool option (GhidraOptions.OPTION_DOCKING_WINDOWS_ON_TOP)
	 * for whether docked windows will always be shown on top of their parent windows.
	 * @return value of the Tool option, GhidraOptions.OPTION_DOCKING_WINDOWS_ON_TOP
	 */
	public boolean isWindowsOnTop() {
		return winMgr.isWindowsOnTop();
	}

	/**
	 * Add popup listener that is notified when the popup menu is about to be
	 * displayed.
	 *
	 * @param listener listener that is notified when the popup menu is to
	 * be displayed
	 */
	public void addPopupListener(PopupListener listener) {
		popupListeners.add(listener);
	}

	/**
	 * Returns the manage plugins dialog that is currently
	 * being used.
	 * @return the current manage plugins dialog
	 */
	public ManagePluginsDialog getManagePluginsDialog() {
		return manageDialog;
	}

	/**
	 * Displays the manage plugins dialog.
	 * @param addSaveActions if true show save actions
	 * @param isNewTool true if creating a new tool
	 */
	public void showConfig(boolean addSaveActions, boolean isNewTool) {
		if (manageDialog != null) {
			manageDialog.close();
		}
		manageDialog = new ManagePluginsDialog(this, addSaveActions, isNewTool);
		showDialog(manageDialog);
	}

	/**
	 * Displays the extensions installation dialog.
	 */
	public void showExtensions() {
		if (extensionTableProvider != null) {
			extensionTableProvider.close();
		}
		extensionTableProvider = new ExtensionTableProvider(this);
		showDialog(extensionTableProvider);
	}

	/**
	 * Remove popup listener
	 * @param listener listener that is notified when the popup menu is to
	 * be displayed
	 */
	public void removePopupListener(PopupListener listener) {
		popupListeners.remove(listener);
	}

	/**
	 * Adds the action to the tool.
	 * @param action the action to be added.
	 */
	@Override
	public void addAction(DockingActionIf action) {
		actionMgr.addToolAction(action);
	}

	/**
	 * Add an action that is associated with the given provider. The action
	 * works only in the context of the provider, and not across the tool
	 * as for a "global" action.
	 * @param provider provider that has a visible component in the tool
	 * @param action local action to associate with the provider
	 */
	@Override
	public void addLocalAction(ComponentProvider provider, DockingActionIf action) {
		actionMgr.addLocalAction(provider, action);
	}

	/**
	 * Removes the given action from the tool
	 * @param action the action to be removed.
	 */
	@Override
	public void removeAction(DockingActionIf action) {
		actionMgr.removeToolAction(action);
	}

	/**
	 * Adds a visible component to the tool.
	 * @param provider The component provider that provides the component to be added.
	 * @param show flag to initially show the component.
	 */
	@Override
	public void addComponentProvider(final ComponentProvider provider, final boolean show) {
		Runnable r = () -> winMgr.addComponent(provider, show);
		SystemUtilities.runSwingNow(r);
	}

	/**
	 * Set whether a component's header should be shown; the header is the component that
	 * is dragged in order to move the component within the tool, or out of the tool
	 * into a separate window
	 *
	 * @param provider provider of the visible component in the tool
	 * @param b true means to show the header
	 */
	public void showComponentHeader(ComponentProvider provider, boolean b) {
		winMgr.showComponentHeader(provider, b);
	}

	@Override
	public boolean isActive(ComponentProvider provider) {
		return winMgr.isActiveProvider(provider);
	}

	/**
	 * Hides or shows the component associated with the given provider.
	 * @param provider the provider of the component to be hidden or shown.
	 * @param visibleState true to show the component, false to hide it.
	 */
	@Override
	public void showComponentProvider(final ComponentProvider provider,
			final boolean visibleState) {
		Runnable r = () -> winMgr.showComponent(provider, visibleState);
		SystemUtilities.runSwingNow(r);
	}

	@Override
	public void toFront(final ComponentProvider provider) {
		Runnable r = () -> winMgr.toFront(provider);
		SystemUtilities.runSwingNow(r);
	}

	@Override
	public void removeComponentProvider(final ComponentProvider provider) {
		Runnable r = () -> actionMgr.removeComponent(provider);
		SystemUtilities.runSwingNow(r);
	}

	@Override
	public void updateTitle(ComponentProvider provider) {
		winMgr.updateTitle(provider);
	}

	@Override
	public boolean isVisible(ComponentProvider provider) {
		return winMgr.isVisible(provider);
	}

	@Override
	public boolean isVisible() {
		return winMgr.isVisible();
	}

	/**
	 * @see ghidra.framework.model.Tool#setVisible(boolean)
	 */
	@Override
	public void setVisible(boolean visibility) {
		winMgr.setVisible(visibility);
	}

	@Override
	public void toFront() {
		JFrame frame = winMgr.getRootFrame();
		if (frame.getExtendedState() == Frame.ICONIFIED) {
			frame.setExtendedState(Frame.NORMAL);
		}
		frame.toFront();
	}

	/**
	 * Returns the tool's frame
	 * @return the tool's frame
	 */
	@Override
	public JFrame getToolFrame() {
		return winMgr.getRootFrame();
	}

	/** Install any services that are not provided by plugins */
	private void installServices() {
		serviceMgr.addService(ProjectDataService.class,
			(ProjectDataService) () -> project.getProjectData());
	}

	/**
	 * Returns true if the specified <code>serviceInterface</code>
	 * is a valid service that exists in this tool.
	 * @param serviceInterface the service interface
	 * @return true if the specified <code>serviceInterface</code>
	 */
	public boolean isService(Class<?> serviceInterface) {
		return serviceMgr.isService(serviceInterface);
	}

	@Override
	public <T> T getService(Class<T> c) {
		return serviceMgr.getService(c);
	}

	/**
	 * Get the objects that implement the given service.
	 * @param c service class
	 * @return array of Objects that implement the service, c.
	 */
	public <T> T[] getServices(Class<T> c) {
		return serviceMgr.getServices(c);
	}

	<T> void addService(Class<T> interfaceClass, T service) {
		serviceMgr.addService(interfaceClass, service);
	}

	void removeService(Class<?> interfaceClass, Object service) {
		serviceMgr.removeService(interfaceClass, service);
	}

	@Override
	public void addServiceListener(ServiceListener listener) {
		serviceMgr.addServiceListener(listener);
	}

	@Override
	public void removeServiceListener(ServiceListener listener) {
		serviceMgr.removeServiceListener(listener);
	}

	/**
	  * Set the status information.
	  * @param text string to be displayed in the Status display area
	  * @param beep whether to beep or not
	  */
	public void setStatusInfo(String text, boolean beep) {
		winMgr.setStatusText(text, beep);
	}

	@Override
	public void setStatusInfo(String text) {
		winMgr.setStatusText(text);
	}

	/**
	 * Clear the status information.
	 */
	public void clearStatusInfo() {
		winMgr.setStatusText("");
	}

	/**
	 * A convenience method to make an attention-grabbing noise to the user
	 */
	public void beep() {
		DockingWindowManager.beep();
	}

	/**
	 * Sets the provider that should get the default focus when no component has focus.
	 * @param provider the provider that should get the default focus when no component has focus.
	 */
	public void setDefaultComponent(ComponentProvider provider) {
		winMgr.setDefaultComponent(provider);
	}

	@Override
	public ToolTemplate getToolTemplate(boolean includeConfigState) {
		throw new UnsupportedOperationException(
			"You cannot create templates for generic tools: " + getClass().getName());
	}

	@Override
	public ToolTemplate saveToolToToolTemplate() {
		setConfigChanged(false);
		optionsMgr.removeUnusedOptions();
		return getToolTemplate(true);
	}

	@Override
	public Element saveWindowingDataToXml() {
		throw new UnsupportedOperationException(
			"You cannot persist generic tools: " + getClass().getName());
	}

	@Override
	public void restoreWindowingDataFromXml(Element windowData) {
		throw new UnsupportedOperationException(
			"You cannot persist generic tools: " + getClass().getName());
	}

	@Override
	public boolean acceptDomainFiles(DomainFile[] data) {
		return pluginMgr.acceptData(data);
	}

	@Override
	public void addPropertyChangeListener(PropertyChangeListener l) {
		propertyChangeMgr.addPropertyChangeListener(l);

	}

	@Override
	public void addToolListener(ToolListener listener) {
		eventMgr.addToolListener(listener);
	}

	/**
	 * Returns true if there is at least one tool listening to this tool's plugin events.
	 */
	public boolean hasToolListeners() {
		return eventMgr.hasToolListeners();
	}

	@Override
	public void exit() {
		dispose();
	}

	protected void dispose() {
		isDisposed = true;

		pluginMgr.close();
		if (project != null) {
			if (project.getToolManager() != null) {
				project.getToolManager().disconnectTool(this);
			}
		}

		if (manageDialog != null) {
			manageDialog.close();
		}

		winMgr.setVisible(false);
		eventMgr.clearLastEvents();
		pluginMgr.dispose();
		actionMgr.dispose();

		if (project != null) {
			project.releaseFiles(this);
		}

		optionsMgr.dispose();

		disposeManagers();
		winMgr.dispose();
	}

	private void disposeManagers() {
		taskMgr.dispose();
	}

	@Override
	public void firePluginEvent(PluginEvent event) {
		eventMgr.fireEvent(event);
	}

	@Override
	public String[] getConsumedToolEventNames() {
		return eventMgr.getEventsConsumed();
	}

	@Override
	public DomainFile[] getDomainFiles() {
		return pluginMgr.getData();
	}

	@Override
	public ImageIcon getIcon() {
		return iconURL.getIcon();
	}

	@Override
	public ToolIconURL getIconURL() {
		return iconURL;
	}

	@Override
	public String getInstanceName() {
		return instanceName;
	}

	@Override
	public String getName() {
		return fullName;
	}

	@Override
	public Class<?>[] getSupportedDataTypes() {
		return pluginMgr.getSupportedDataTypes();
	}

	@Override
	public String[] getToolEventNames() {
		return eventMgr.getEventsProduced();
	}

	@Override
	public String getToolName() {
		return toolName;
	}

	@Override
	public void putInstanceName(String newInstanceName) {
		this.instanceName = newInstanceName;
		if (instanceName.length() == 0) {
			fullName = toolName;
		}
		else {
			fullName = toolName + "(" + instanceName + ")";
		}
		updateTitle();
	}

	@Override
	public void removePropertyChangeListener(PropertyChangeListener l) {
		propertyChangeMgr.removePropertyChangeListener(l);

	}

	@Override
	public void removeToolListener(ToolListener listener) {
		eventMgr.removeToolListener(listener);
	}

	@Override
	public void restoreDataStateFromXml(Element root) {
		pluginMgr.restoreDataStateFromXml(root);
		setConfigChanged(false);
	}

	@Override
	public Element saveDataStateToXml(boolean savingProject) {
		return pluginMgr.saveDataStateToXml(savingProject);
	}

	protected boolean restoreFromXml(Element root) {
		toolName = root.getAttributeValue(ToolTemplate.TOOL_NAME_XML_NAME);
		instanceName = root.getAttributeValue(ToolTemplate.TOOL_INSTANCE_NAME_XML_NAME);

		if (instanceName.length() == 0) {
			fullName = toolName;
		}
		else {
			fullName = toolName + "(" + instanceName + ")";
		}
		SplashScreen.updateSplashScreenStatus("Loading " + fullName + " ...");

		restoreOptionsFromXml(root);
		setDefaultOptionValues();
		boolean hasErrors = false;
		try {
			pluginMgr.restorePluginsFromXml(root);
		}
		catch (PluginException e) {
			hasErrors = true;
			Msg.showError(this, getToolFrame(), "Error Restoring Plugins", e.getMessage());
		}

		winMgr.restoreFromXML(root);
		winMgr.setToolName(fullName);
		return hasErrors;
	}

	@Override
	public Element saveToXml(boolean includeConfigState) {
		Element root = new Element("TOOL");

		root.setAttribute(TOOL_NAME_XML_NAME, toolName);
		root.setAttribute(TOOL_INSTANCE_NAME_XML_NAME, instanceName);

		root.addContent(optionsMgr.getConfigState());
		pluginMgr.saveToXml(root, includeConfigState);
		winMgr.saveToXML(root);
		return root;
	}

	@Override
	public void setConfigChanged(boolean changed) {
		super.setConfigChanged(changed);
		if (manageDialog != null) {
			manageDialog.stateChanged();
		}
	}

	@Override
	public void setIconURL(ToolIconURL newIconURL) {
		if (newIconURL == null) {
			throw new NullPointerException("iconURL cannot be null.");
		}
		setConfigChanged(true);
		if (newIconURL.equals(iconURL)) {
			return;
		}

		ImageIcon oldValue = iconURL.getSmallIcon();
		iconURL = newIconURL;
		ImageIcon newValue = iconURL.getSmallIcon();

		propertyChangeMgr.firePropertyChange(ICON_PROPERTY_NAME, oldValue, newValue);
		winMgr.setIcon(newValue);
	}

	@Override
	public void setToolName(String name) {
		String oldName = toolName;
		toolName = name;
		if (instanceName.length() == 0) {
			fullName = toolName;
		}
		else {
			fullName = toolName + "(" + instanceName + ")";
		}
		winMgr.setToolName(fullName);
		propertyChangeMgr.firePropertyChange(TOOL_NAME_PROPERTY, oldName, toolName);
	}

	@Override
	public void processToolEvent(PluginEvent toolEvent) {
		eventMgr.processToolEvent(toolEvent);
	}

	/**
	 * Call the applyTo() method on the given command to make some change to
	 * the domain object; the command is done in the AWT thread, therefore,
	 * the command that is to be executed should be a relatively quick operation
	 * so that the event queue does not appear to "hang." For lengthy
	 * operations, the command should be done in a background task.
	 * @param command command to apply
	 * @param obj domain object that the command will be applied to
	 * @return status of the command's applyTo() method
	 * @see #executeBackgroundCommand(BackgroundCommand, UndoableDomainObject)
	 */
	public boolean execute(Command command, DomainObject obj) {
		return taskMgr.execute(command, obj);
	}

	/**
	 * Return whether there is a command being executed.
	 */
	public boolean isExecutingCommand() {
		return taskMgr.isBusy();
	}

	/**
	 * @return true if the current thread group or its ancestors is
	 * a member of this tools background task thread group, else false
	 */
	public boolean threadIsBackgroundTaskThread() {
		ThreadGroup taskGroup = taskMgr.getTaskThreadGroup();
		ThreadGroup group = Thread.currentThread().getThreadGroup();
		while (group != null && group != taskGroup) {
			group = group.getParent();
		}
		return group == taskGroup;
	}

	/**
	 * Start a new thread that will call the given command's applyTo()
	 * method to make some change in the domain object. This method should
	 * be called for an operation that could potentially take a long time to
	 * complete.
	 * @param cmd command that will be executed in another thread (not the
	 * AWT Thread)
	 * @param obj domain object that the command will be applied to
	 */
	public void executeBackgroundCommand(BackgroundCommand cmd, UndoableDomainObject obj) {
		taskMgr.executeCommand(cmd, obj);
	}

	/**
	 * Cancel any running command and clear the command queue.
	 * @param wait if true wait for current task to cancel cleanly
	 */
	public void terminateBackgroundCommands(boolean wait) {
		taskMgr.stop(wait);
	}

	/**
	 * Add the given background command to a queue that is processed after the
	 * main background command completes.
	 * @param cmd background command to submit
	 * @param obj the domain object to be modified by the command.
	 */
	public void scheduleFollowOnCommand(BackgroundCommand cmd, UndoableDomainObject obj) {
		taskMgr.scheduleFollowOnCommand(cmd, obj);
	}

	/**
	 * Get the options for the given category name; if no options exist with
	 * the given name, then one is created.
	 */
	@Override
	public ToolOptions getOptions(String categoryName) {
		return optionsMgr.getOptions(categoryName);
	}

	/**
	 * Updates saved options from an old name to a new name.  NOTE: this must be called before
	 * any calls to register or get options.
	 * @param oldName the old name of the options.
	 * @param newName the new name of the options.
	 */
	public void registerOptionsNameChange(String oldName, String newName) {
		optionsMgr.registerOptionNameChanged(oldName, newName);
	}

	/**
	  * Return true if there is an options category with the given name
	  * @param category name of the options set
	  * @return true if there is an options category with the given name
	  */
	public boolean hasOptions(String category) {
		return optionsMgr.hasOptions(category);
	}

	/**
	 * Returns options manager
	 */
	OptionsManager getOptionsManager() {
		return optionsMgr;
	}

	/**
	 * Get all options.
	 * @return zero-length array if no options exist.
	 */
	public ToolOptions[] getOptions() {
		return optionsMgr.getOptions();
	}

	/**
	 * Launch the task in a new thread.
	 * @param task task to run in a new thread
	 * @param delay number of milliseconds to delay the display of task monitor dialog
	 */
	public void execute(Task task, int delay) {
		new TaskLauncher(task, getToolFrame(), delay);
	}

	/**
	 * Launch the task in a new thread.
	 * @param task task to run in a new thread
	 */
	public void execute(Task task) {
		new TaskLauncher(task, winMgr.getActiveWindow());
	}

	/**
	 * Get the project associated with this tool.  Null will be returned if there is no
	 * project open or if this tool does not use projects.
	 *
	 * @return null if there is no open project
	 */
	public Project getProject() {
		return project;
	}

	/**
	 * Returns the project manager associated with this tool.
	 *
	 * <P>Null will be returned if this tool does not use projects.
	 *
	 * @return the project manager associated with this tool
	 */
	public ProjectManager getProjectManager() {
		return projectManager;
	}

	/**
	 * Returns an object that provides fundamental services that plugins
	 * can use.
	 */
	public ToolServices getToolServices() {
		return toolServices;
	}

	/**
	 * Sets the subtitle on the tool; the subtitle is extra text in the title.
	 * @param subTitle the subtitle to display on the tool
	 */
	public void setSubTitle(String subTitle) {
		this.subTitle = subTitle;
		updateTitle();
	}

	/**
	 * Add a plugin to the tool.
	 * @param className name of the plugin class, e.g., "MyPlugin.class.getName()"
	 * @throws PluginException if the plugin could not be constructed, or
	 * there was problem executing its init() method, or if a plugin of this
	 * class already exists in the tool
	 */
	public void addPlugin(String className) throws PluginException {
		checkedRunSwingNow(() -> {
			addPlugins(new String[] { className });
		}, PluginException.class);
	}

	/**
	 * Add plugins to the tool.
	 * @param classNames array of plugin class names
	 * @throws PluginException if a plugin could not be constructed, or
	 * there was problem executing its init() method, or if a plugin of this
	 * class already exists in the tool
	 */
	public void addPlugins(String[] classNames) throws PluginException {
		try {
			pluginMgr.addPlugins(classNames);
		}
		finally {
			setConfigChanged(true);
		}
	}

	public void addPlugin(Plugin p) throws PluginException {
		pluginMgr.addPlugin(p);
		setConfigChanged(true);
	}

	public boolean hasUnsavedData() {
		return pluginMgr.hasUnsavedData();
	}

	/**
	 * Remove the array of plugins from the tool.
	 * @param plugins array of plugins to remove
	 */
	public void removePlugins(Plugin[] plugins) {
		SystemUtilities.runSwingNow(() -> {
			try {
				pluginMgr.removePlugins(plugins);
			}
			finally {
				setConfigChanged(true);
			}
		});
	}

	/**
	 * Return a list of plugins in the tool
	 * @return list of plugins in the tool
	 */
	public List<Plugin> getManagedPlugins() {
		return pluginMgr.getPlugins();
	}

	/**
	 * Save this tool's configuration.
	 */
	public void saveTool() {
		toolServices.saveTool(this);
	}

	/**
	 * Triggers a 'Save As' dialog that allows the user to save off the tool under a different
	 * name.  This returns true if the user performed a save.
	 */
	public boolean saveToolAs() {
		return dialogMgr.saveToolAs();
	}

	/**
	 * Add a status component to the tool.
	 *
	 * @param c component to add
	 * @param addBorder true if a border should be added to the component
	 * @param rightSide true if the component should be placed in the right side of the tool
	 */
	public void addStatusComponent(JComponent c, boolean addBorder, boolean rightSide) {
		winMgr.addStatusItem(c, addBorder, rightSide);
	}

	/**
	 * Remove the status component.
	 * @param c status component to remove
	 */
	public void removeStatusComponent(JComponent c) {
		winMgr.removeStatusItem(c);
	}

	@Override
	public List<DockingActionIf> getDockingActionsByFullActionName(String fullActionName) {
		Set<DockingActionIf> set = new HashSet<>();
		set.addAll(actionMgr.getDockingActionsByFullActionName(fullActionName));
		set.addAll(winMgr.getActions(fullActionName));
		return new ArrayList<>(set);
	}

	@Override
	public List<DockingActionIf> getDockingActionsByOwnerName(String owner) {
		List<DockingActionIf> actions = actionMgr.getActions(owner);
		return actions;
	}

	@Override
	public List<DockingActionIf> getAllActions() {
		return actionMgr.getAllActions();
	}

	@Override
	public void removeLocalAction(ComponentProvider provider, DockingActionIf action) {
		actionMgr.removeProviderAction(provider, action);
	}

	protected void addExitAction() {
		DockingAction exitAction = new DockingAction("Exit Ghidra", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				AppInfo.exitGhidra();
			}
		};
		exitAction.setHelpLocation(
			new HelpLocation(ToolConstants.FRONT_END_HELP_TOPIC, exitAction.getName()));
		exitAction.setMenuBarData(new MenuData(
			new String[] { ToolConstants.MENU_FILE, "E&xit Ghidra" }, null, "Window_Z"));

		if (Platform.CURRENT_PLATFORM.getOperatingSystem() != OperatingSystem.MAC_OS_X) {
			// Only install keybinding on non-OSX systems, as OSX handles the Command-Q
			// quit action for us.  If we put the binding on, then we will get the
			// callback twice.
			exitAction.setKeyBindingData(
				new KeyBindingData(KeyEvent.VK_Q, DockingUtils.CONTROL_KEY_MODIFIER_MASK));
		}

		exitAction.setEnabled(true);
		addAction(exitAction);
	}

	protected void addOptionsAction() {
		DockingAction optionsAction = new DockingAction("Edit Options", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				optionsMgr.editOptions();
			}

			@Override
			public boolean shouldAddToWindow(boolean isMainWindow, Set<Class<?>> contextTypes) {
				return isMainWindow || !contextTypes.isEmpty();
			}
		};
		optionsAction.setHelpLocation(
			new HelpLocation(ToolConstants.FRONT_END_HELP_TOPIC, "Tool Options"));
		MenuData menuData =
			new MenuData(new String[] { ToolConstants.MENU_EDIT, "&Tool Options..." }, null,
				ToolConstants.TOOL_OPTIONS_MENU_GROUP);
		menuData.setMenuSubGroup(ToolConstants.TOOL_OPTIONS_MENU_GROUP);
		optionsAction.setMenuBarData(menuData);

		optionsAction.setEnabled(true);
		addAction(optionsAction);
	}

	protected void addSaveToolAction() {

		DockingAction saveAction = new DockingAction("Save Tool", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				saveTool();
			}
		};
		MenuData menuData =
			new MenuData(new String[] { ToolConstants.MENU_FILE, "Save Tool" }, null, "Tool");
		menuData.setMenuSubGroup("1Tool");
		saveAction.setMenuBarData(menuData);
		saveAction.setEnabled(true);
		saveAction.setHelpLocation(new HelpLocation("Tool", "Save Tool"));

		DockingAction saveAsAction = new DockingAction("Save Tool As", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				saveToolAs();
			}
		};
		menuData =
			new MenuData(new String[] { ToolConstants.MENU_FILE, "Save Tool As..." }, null, "Tool");
		menuData.setMenuSubGroup("2Tool");
		saveAsAction.setMenuBarData(menuData);

		saveAsAction.setEnabled(true);
		saveAsAction.setHelpLocation(new HelpLocation("Tool", "Tool_Changes"));

		addAction(saveAction);
		addAction(saveAsAction);
	}

	protected void addExportToolAction() {
		exportToolAction = new DockingAction("Export Tool", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				dialogMgr.exportTool();
			}
		};
		MenuData menuData =
			new MenuData(new String[] { ToolConstants.MENU_FILE, "Export Tool..." }, null, "Tool");
		menuData.setMenuSubGroup("3Tool");
		exportToolAction.setMenuBarData(menuData);

		exportToolAction.setEnabled(true);
		exportToolAction.setHelpLocation(new HelpLocation("Tool", "Export Tool"));
		addAction(exportToolAction);
	}

	protected void addHelpActions() {

		DockingAction action = new DockingAction("About Ghidra", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				DockingWindowManager.showDialog(new AboutDialog());
			}

			@Override
			public boolean shouldAddToWindow(boolean isMainWindow, Set<Class<?>> contextTypes) {
				return true;
			}
		};
		action.setMenuBarData(
			new MenuData(new String[] { ToolConstants.MENU_HELP, "&About Ghidra" }, null, "ZZA"));

		action.setHelpLocation(new HelpLocation(ToolConstants.ABOUT_HELP_TOPIC, "About_Ghidra"));
		action.setEnabled(true);
		addAction(action);

		DockingAction userAgreementAction = new DockingAction("User Agreement", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				DockingWindowManager.showDialog(new UserAgreementDialog(false, false));
			}

			@Override
			public boolean shouldAddToWindow(boolean isMainWindow, Set<Class<?>> contextTypes) {
				return true;
			}
		};
		userAgreementAction.setMenuBarData(
			new MenuData(new String[] { ToolConstants.MENU_HELP, "&User Agreement" }, null,
				ToolConstants.HELP_CONTENTS_MENU_GROUP));
		userAgreementAction.setHelpLocation(
			new HelpLocation(ToolConstants.ABOUT_HELP_TOPIC, "User_Agreement"));

		userAgreementAction.setEnabled(true);
		addAction(userAgreementAction);

		final ErrorReporter reporter = ErrLogDialog.getErrorReporter();
		if (reporter != null) {
			action = new DockingAction("Report Bug", "Tool") {
				@Override
				public void actionPerformed(ActionContext context) {
					reporter.report(getToolFrame(), "User Bug Report", null);
				}

				@Override
				public boolean shouldAddToWindow(boolean isMainWindow, Set<Class<?>> contextTypes) {
					return true;
				}
			};
			action.setMenuBarData(new MenuData(
				new String[] { ToolConstants.MENU_HELP, "&Report Bug..." }, null, "BBB"));

			action.setHelpLocation(new HelpLocation("ErrorReporting", "Report_Bug"));
			action.setEnabled(true);
			addAction(action);
		}

		HelpService help = Help.getHelpService();
		action = new DockingAction("Contents", "Tool") {
			@Override
			public void actionPerformed(ActionContext context) {
				help.showHelp(null, false, getToolFrame());
			}

			@Override
			public boolean shouldAddToWindow(boolean isMainWindow, Set<Class<?>> contextTypes) {
				return true;
			}
		};
		action.setMenuBarData(new MenuData(new String[] { ToolConstants.MENU_HELP, "&Contents" },
			null, ToolConstants.HELP_CONTENTS_MENU_GROUP));

		action.setEnabled(true);
		action.setHelpLocation(new HelpLocation("Misc", "Welcome_to_Ghidra_Help"));

		addAction(action);
	}

	/**
	 * Clear the list of events that were last generated.
	 *
	 */
	public void clearLastEvents() {
		eventMgr.clearLastEvents();
	}

	@Override
	public List<DockingActionIf> getPopupActions(ActionContext context) {

		List<DockingActionIf> actionList = new ArrayList<>();
		for (PopupListener pl : popupListeners) {
			List<DockingActionIf> actions = pl.getPopupActions(context);
			if (actions != null) {
				actionList.addAll(actions);
			}
		}
		return actionList;
	}

	/**
	 * Close this tool:
	 * <OL>
	 * 	<LI>if there are no tasks running.
	 * 	<LI>resolve the state of any plugins so they can be closed.
	 * 	<LI>Prompt the user to save any changes.
	 * 	<LI>close all associated plugins (this closes the domain object if one is open).
	 * 	<LI>pop up dialog to save the configuration if it has changed.
	 * 	<LI>notify the project tool services that this tool is going away.
	 * </OL>
	 */
	@Override
	public void close() {
		if (canClose(false) && pluginMgr.saveData()) {
			doClose();
		}
	}

	/**
	 * Close this tool:
	 * <OL>
	 * 	<LI>if there are no tasks running.
	 * 	<LI>close all associated plugins (this closes the domain object if one is open).
	 * 	<LI>pop up dialog to save the configuration if it has changed;
	 * 	<LI>notify the project tool services that this tool is going away.
	 * </OL>
	 */
	private void doClose() {

		if (!doSaveTool()) {
			return; // if cancelled, don't close
		}

		exit();
		toolServices.closeTool(this);
	}

	@Override
	public boolean shouldSave() {
		return configChangedFlag; // ignore the window layout changes
	}

	/** Called when it is time to save the tool.  Handles auto-saving logic. */
	protected boolean doSaveTool() {
		if (toolServices.canAutoSave(this)) {
			saveTool();
		}
		else {
			if (configChangedFlag) {
				int result = OptionDialog.showOptionDialog(getToolFrame(), SAVE_DIALOG_TITLE,
					"This tool has changed.  There are/were multiple instances of this tool\n" +
						"running and Ghidra cannot determine if this tool instance should\n" +
						"automatically be saved.  Do you want to save the configuration of this tool\n" +
						"instance?",
					"Save", "Save As...", "Don't Save", OptionDialog.WARNING_MESSAGE);
				if (result == OptionDialog.CANCEL_OPTION) {
					return false;
				}
				if (result == OptionDialog.OPTION_ONE) {
					saveTool();
				}
				else if (result == OptionDialog.OPTION_TWO) {
					boolean didSave = saveToolAs();
					if (!didSave) {
						return doSaveTool();
					}
				}
				// option 3 is don't save; just exit
			}
		}
		return true;
	}

	/**
	 * Can this tool be closed?
	 * <br>Note: This forces plugins to terminate any tasks they have running and
	 * apply any unsaved data to domain objects or files. If they can't do
	 * this or the user cancels then this returns false.
	 * @return false if this tool has tasks in progress or can't be closed
	 * since the user has unfinished/unsaved changes.
	 */
	@Override
	public boolean canClose(boolean isExiting) {
		if (taskMgr.isBusy()) {
			if (isExiting) {
				int result = OptionDialog.showYesNoDialog(getToolFrame(),
					"Tool Busy Executing Task",
					"The tool is busy performing a background task.\n If you continue the" +
						" task may be terminated and some work may be lost!\n\nContinue anyway?");
				if (result == OptionDialog.NO_OPTION) {
					return false;
				}
				taskMgr.stop(false);
			}
			else {
				beep();
				Msg.showInfo(getClass(), getToolFrame(), "Tool Busy",
					"You must stop all background tasks before exiting.");
				return false;
			}
		}
		if (!pluginMgr.canClose()) {
			return false;
		}
		return true;
	}

	/**
	 * Can the domain object be closed?
	 * <br>Note: This forces plugins to terminate any tasks they have running for the
	 * indicated domain object and apply any unsaved data to the domain object. If they can't do
	 * this or the user cancels then this returns false.
	 * @return false any of the plugins reports that the domain object
	 * should not be closed
	 */
	public boolean canCloseDomainObject(DomainObject dObj) {
		if (taskMgr.hasTasksForDomainObject(dObj)) {
			Msg.showInfo(getClass(), getToolFrame(), "Close " + dObj.getName() + " Failed",
				"The tool is currently working in the background on " + dObj.getName() +
					".\nPlease stop the background processing first.");

			return false;
		}
		return pluginMgr.canCloseDomainObject(dObj);
	}

	@Override
	public boolean canCloseDomainFile(DomainFile domainFile) {
		Object consumer = new Object();
		DomainObject domainObject = domainFile.getOpenedDomainObject(consumer);
		if (domainObject == null) {
			return true;
		}
		try {
			return canCloseDomainObject(domainObject);
		}
		finally {
			domainObject.release(consumer);
		}
	}

	/**
	 * Called when the domain object is about to be saved; this allows any plugin that has
	 * a cache to flush out to the domain object.
	 * @param dobj domain object that is about to be saved
	 */
	public void prepareToSave(DomainObject dobj) {
		pluginMgr.prepareToSave(dobj);
	}

	/**
	 * Sets the size of the tool's main window
	 * @param width width in pixels
	 * @param height height in pixels
	 */
	public void setSize(int width, int height) {
		winMgr.getMainWindow().setSize(new Dimension(width, height));
	}

	/**
	 * Return the dimension of this tool's frame.
	 * @return dimension of this tool's frame
	 */
	public Dimension getSize() {
		return winMgr.getMainWindow().getSize();
	}

	/**
	 * Set the location of this tool's frame on the screen.
	 * @param x screen x coordinate
	 * @param y screen y coordinate
	 */
	public void setLocation(int x, int y) {
		winMgr.getMainWindow().setLocation(x, y);
	}

	/**
	 * Return the location of this tool's frame on the screen.
	 * @return location of this tool's frame
	 */
	public Point getLocation() {
		return winMgr.getMainWindow().getLocation();
	}

	private void updateTitle() {
		String title = fullName;
		if (subTitle != null) {
			title += ": " + subTitle;
		}
		winMgr.setToolName(title);
	}

	protected void restoreOptionsFromXml(Element root) {
		optionsMgr.setConfigState(root.getChild("OPTIONS"));
		actionMgr.restoreKeyBindings();
		setToolOptionsHelpLocation();
	}

	protected void setProject(Project project) {
		this.project = project;
		if (project != null) {
			toolServices = project.getToolServices();
		}
	}

	protected void restorePluginsFromXml(Element elem) throws PluginException {
		pluginMgr.restorePluginsFromXml(elem);

	}

	PluginEvent[] getLastEvents() {
		return eventMgr.getLastEvents();
	}

	void removeAll(String owner) {
		actionMgr.removeToolActions(owner);
		winMgr.removeAll(owner);
	}

	void registerEventProduced(Class<? extends PluginEvent> eventClass) {
		eventMgr.addEventProducer(eventClass);
	}

	void addEventListener(Class<? extends PluginEvent> eventClass, PluginEventListener listener) {
		eventMgr.addEventListener(eventClass, listener);
	}

	void unregisterEventProduced(Class<? extends PluginEvent> eventClass) {
		eventMgr.removeEventProducer(eventClass);
	}

	public void addListenerForAllPluginEvents(PluginEventListener listener) {
		eventMgr.addAllEventListener(listener);
	}

	public void removeListenerForAllPluginEvents(PluginEventListener listener) {
		eventMgr.removeAllEventListener(listener);
	}

	void removeEventListener(Class<? extends PluginEvent> eventClass,
			PluginEventListener listener) {
		eventMgr.removeEventListener(eventClass, listener);
	}

	/**
	 * Remove the event listener by className; the plugin registered
	 * for events, but the construction failed.
	 * @param className class name of the plugin that is the event listener
	 */
	void removeEventListener(String className) {
		eventMgr.removeEventListener(className);
	}

	/**
	 * Display an text edit box on top of the specified component.
	 * @param defaultText initial text to be displayed in edit box
	 * @param comp component over which the edit box will be placed
	 * @param rect specifies the bounds of the edit box relative to the
	 * component.  The height is ignored.  The default text field height
	 * is used as the preferred height.
	 * @param listener when the edit is complete, this listener is notified
	 * with the new text.  The edit box is dismissed prior to notifying
	 * the listener.
	 */
	public void showEditWindow(String defaultText, Component comp, Rectangle rect,
			EditListener listener) {
		winMgr.showEditWindow(defaultText, comp, rect, listener);
	}

	/**
	 * Set the menu group associated with a cascaded submenu.  This allows
	 * a cascading menu item to be grouped with a specific set of actions.
	 * The default group for a cascaded submenu is the name of the submenu.
	 *
	 * @param menuPath menu name path where the last element corresponds
	 * to the specified group name.
	 * @param group group name
	 * @see #setMenuGroup(String[], String, String)
	 */
	public void setMenuGroup(String[] menuPath, String group) {
		winMgr.setMenuGroup(menuPath, group);
	}

	/**
	 * Set the menu group associated with a cascaded submenu.  This allows
	 * a cascading menu item to be grouped with a specific set of actions.
	 * <p>
	 * The default group for a cascaded submenu is the name of the submenu.
	 * <p>
	 *
	 * @param menuPath menu name path where the last element corresponds to the specified group name.
	 * @param group group name
	 * @param menuSubGroup the name used to sort the cascaded menu within other menu items at
	 *                     its level
	 */
	public void setMenuGroup(String[] menuPath, String group, String menuSubGroup) {
		winMgr.setMenuGroup(menuPath, group, menuSubGroup);
	}

	/**
	 * Cancel the current task in the tool.
	 */
	public void cancelCurrentTask() {
		this.taskMgr.cancelCurrentTask();
	}

	private void setToolOptionsHelpLocation() {
		Options opt = getOptions(ToolConstants.TOOL_OPTIONS);
		opt.setOptionsHelpLocation(new HelpLocation("Tool", "OptionsForTool"));

		opt = getOptions(DockingToolConstants.KEY_BINDINGS);
		opt.setOptionsHelpLocation(new HelpLocation("Tool", "KeyBindings_Option"));
	}

	public TransientToolState getTransientState() {
		return pluginMgr.getTransientState();
	}

	public UndoRedoToolState getUndoRedoToolState(DomainObject domainObject) {
		return pluginMgr.getUndoRedoToolState(domainObject);
	}

	/**
	 * Shows the dialog using the active top-level window (often the tool's root frame)
	 * as a parent.  Also, remembers any
	 * size and location adjustments made by the user for the next time the dialog is shown.
	 *
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 */
	@Override
	public void showDialog(DialogComponentProvider dialogComponent) {
		DockingWindowManager.showDialog(dialogComponent);
	}

	/**
	 * Shows the dialog using the tool's currently active window as a parent.  Also,
	 * remembers any size and location adjustments made by the user for the next
	 * time the dialog is shown.
	 *
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 */
	public void showDialogOnActiveWindow(DialogComponentProvider dialogComponent) {
		DockingWindowManager.showDialogOnActiveWindow(dialogComponent);
	}

	/**
	 * Shows the dialog using the window containing the given componentProvider as its parent window.
	 * Remembers the last location and size of this dialog for the next time it is shown.
	 *
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 * @param centeredOnProvider the component provider that is used to find a parent window for this dialog.
	 * The dialog is centered on this component provider's component.
	 */
	public void showDialog(DialogComponentProvider dialogComponent,
			ComponentProvider centeredOnProvider) {
		winMgr.showDialog(dialogComponent, centeredOnProvider);
	}

	/**
	 * Shows the dialog using the tool's parent frame, but centers the dialog on the given
	 * component
	 *
	 * @param dialogComponent the DialogComponentProvider object to be shown in a dialog.
	 * @param centeredOnComponent the component on which to center the dialog.
	 */
	public void showDialog(DialogComponentProvider dialogComponent, Component centeredOnComponent) {
		DockingWindowManager.showDialog(getToolFrame(), dialogComponent, centeredOnComponent);
	}

	/**
	 * Returns the ComponentProvider with the given name.  If more than one provider exists with the name,
	 * one will be returned, but it could be any one of them.
	 * @param name the name of the provider to return.
	 * @return a provider with the given name, or null if no providers with that name exist.
	 */
	@Override
	public ComponentProvider getComponentProvider(String name) {
		return winMgr.getComponentProvider(name);
	}

	public Window getActiveWindow() {
		return winMgr.getActiveWindow();
	}

	public ComponentProvider getActiveComponentProvider() {
		return winMgr.getActiveComponentProvider();
	}

	@Override
	public void contextChanged(ComponentProvider provider) {
		winMgr.contextChanged(provider);
	}

	public void addContextListener(DockingContextListener listener) {
		winMgr.addContextListener(listener);
	}

	public void removeContextListener(DockingContextListener listener) {
		winMgr.removeContextListener(listener);
	}

	public void refreshKeybindings() {
		actionMgr.restoreKeyBindings();
	}

	public void setUnconfigurable() {
		isConfigurable = false;
	}

	public boolean isConfigurable() {
		return isConfigurable;
	}

	public void removePreferenceState(String name) {
		winMgr.removePreferenceState(name);
	}

	@Override
	public Window getProviderWindow(ComponentProvider componentProvider) {
		return winMgr.getProviderWindow(componentProvider);
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	private class ToolOptionsListener implements OptionsChangeListener {
		@Override
		public void optionsChanged(ToolOptions options, String name, Object oldValue,
				Object newValue) {
			PluginTool.this.optionsChanged(options, name, oldValue, newValue);
		}
	}

	private interface CheckedRunnable<T extends Throwable> {
		public void run() throws T;
	}

	private <T extends Throwable> void checkedRunSwingNow(CheckedRunnable<T> r,
			Class<T> exceptionClass) throws T {
		AtomicReference<Throwable> caughtException = new AtomicReference<>();
		SystemUtilities.runSwingNow(() -> {
			try {
				r.run();
			}
			catch (Throwable th) {
				caughtException.set(th);
			}
		});
		Throwable th = caughtException.get();
		if (th != null) {
			if (exceptionClass.isInstance(th)) {
				throw exceptionClass.cast(th);
			}
			throw new RuntimeException("Unexpected exception type " + th.getClass(), th);
		}
	}

}
