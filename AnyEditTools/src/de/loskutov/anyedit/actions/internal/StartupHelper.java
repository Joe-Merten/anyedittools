/*******************************************************************************
 * Copyright (c) 2009 Andrey Loskutov.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * Contributor:  Andrey Loskutov - initial API and implementation
 *******************************************************************************/
package de.loskutov.anyedit.actions.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.ICoolBarManager;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarContributionItem;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.internal.WorkbenchWindow;
import org.eclipse.ui.menus.CommandContributionItem;

import de.loskutov.anyedit.AnyEditToolsPlugin;
import de.loskutov.anyedit.IAnyEditConstants;

/**
 * @author Andrey
 */
public class StartupHelper implements IWindowListener {

    private final List<PreExecutionHandler> commandListeners;

    /**
     * Will be run after workbench is started and w.window is opened
     */
    public StartupHelper() {
        super();
        commandListeners = new ArrayList<PreExecutionHandler>();
    }

    public void init() {
        final IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(new DirtyHookRunnable());
        workbench.addWindowListener(this);
    }

    /**
     * Very dirty trick to get internal handle to WorkbenchWindowConfigurer.
     * @param ww
     */
    static IWorkbenchWindowConfigurer getWorkbenchWindowConfigurer(IWorkbenchWindow ww) {
        if (!(ww instanceof WorkbenchWindow)) {
            return null;
        }
        try {
            Method method = WorkbenchWindow.class.getDeclaredMethod(
                    "getWindowConfigurer", null);
            method.setAccessible(true);
            Object object = method.invoke(ww, null);
            if (object instanceof IWorkbenchWindowConfigurer) {
                return (IWorkbenchWindowConfigurer) object;
            }
        } catch (Exception e) {
            AnyEditToolsPlugin.logError(
                    "Can't get handle for WorkbenchWindowConfigurer", e); //$NON-NLS-1$
        }
        return null;
    }

    @Override
    public void windowOpened(IWorkbenchWindow window) {
        DirtyHookRunnable dh = new DirtyHookRunnable();
        dh.run(window);
    }

    @Override
    public void windowActivated(IWorkbenchWindow window) {
        // ignored
    }

    @Override
    public void windowDeactivated(IWorkbenchWindow window) {
        // ignored
    }

    @Override
    public void windowClosed(IWorkbenchWindow window) {
        // ignored
        List<PreExecutionHandler> list = commandListeners;
        for (int i = 0; i < list.size(); i++) {
            PreExecutionHandler listener = list.get(i);
            if(listener.myAction.getWindow() == window) {
                unHookFromCommand(listener);
            }
        }
    }

    private void unHookFromCommand(PreExecutionHandler listener) {
        ICommandService service = (ICommandService) PlatformUI.getWorkbench().getService(
                ICommandService.class);
        Command command = service.getCommand(listener.commandId);
        command.removeExecutionListener(listener);
    }

    private void hookOnCommand(final IDirtyWorkaround myAction, String commandId) {
        ICommandService service = (ICommandService) PlatformUI.getWorkbench().getService(
                ICommandService.class);
        Command command = service.getCommand(commandId);
        PreExecutionHandler listener = new PreExecutionHandler(myAction, commandId);
        command.addExecutionListener(listener);
        commandListeners.add(listener);
    }

    private static final class PreExecutionHandler implements IExecutionListener {

        private final IDirtyWorkaround myAction;
        private final String commandId;

        private PreExecutionHandler(IDirtyWorkaround myAction, String commandId) {
            this.myAction = myAction;
            this.commandId = commandId;
        }

        @Override
        public void notHandled(String command, NotHandledException exception) {
            //
        }

        @Override
        public void postExecuteFailure(String command, ExecutionException exception) {
            //
        }

        @Override
        public void postExecuteSuccess(String command, Object returnValue) {
            //
        }

        @Override
        public void preExecute(String command, ExecutionEvent event) {
            myAction.runBeforeSave();
        }
    }

    private final class DirtyHookRunnable implements Runnable {

        private static final String FILE_SAVE_ALL = "org.eclipse.ui.file.saveAll";

        private static final String FILE_SAVE = "org.eclipse.ui.file.save";

        private static final String FILE_MENU = "file";

        private static final String PRINT_BUTTON_ID = "print";

        private static final String FILE_TOOLBAR = "org.eclipse.ui.workbench.file";

        private DirtyHookRunnable() {
            super();
        }

        @Override
        public void run() {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                return;
            }
            try{
                run(window);
            } catch (Throwable t){
                AnyEditToolsPlugin.logError("Can't run dirty code to replace default actions", t);
            }
        }

        private void run(IWorkbenchWindow window) {
            IWorkbenchWindowConfigurer wwConf = getWorkbenchWindowConfigurer(window);
            if (wwConf == null) {
                return;
            }
            final SpecialSaveAction mySaveAction = new SpecialSaveAction(window);
            final SpecialSaveAllAction mySaveAllAction = new SpecialSaveAllAction(window);

            hookOnCommand(mySaveAction, FILE_SAVE);
            hookOnCommand(mySaveAllAction, FILE_SAVE_ALL);

            // override default save action with custom one, wich extend
            // the default action
            IActionBarConfigurer configurer = wwConf.getActionBarConfigurer();
            configurer.registerGlobalAction(mySaveAction);
            configurer.registerGlobalAction(mySaveAllAction);

            IMenuManager menuBar = configurer.getMenuManager();

            // get "file" menu group
            IContributionItem item = menuBar.find(FILE_MENU);

            if (item instanceof MenuManager) {
                MenuManager menu = (MenuManager) item;
                // replace menu actions with ours
                insert(mySaveAction, menu);
                insert(mySaveAllAction, menu);
            }

            ICoolBarManager coolBar = configurer.getCoolBarManager();
            // get "file" toolbar group
            item = coolBar.find(FILE_TOOLBAR);
            if (item instanceof ToolBarContributionItem) {
                ToolBarContributionItem item2 = (ToolBarContributionItem) item;
                ToolBarManager manager = (ToolBarManager) item2.getToolBarManager();

                int controlIdx = insert(mySaveAction, manager, -1);
                int nextIndex = controlIdx;
                boolean addSaveAll = getPref(IAnyEditConstants.ADD_SAVE_ALL_TO_TOOLBAR);
                if (addSaveAll) {
                    nextIndex++;
                    insert(mySaveAllAction, manager, nextIndex);
                }
                boolean removePrint = getPref(IAnyEditConstants.REMOVE_PRINT_FROM_TOOLBAR);
                if (removePrint) {
                    nextIndex++;
                    remove(manager, nextIndex, PRINT_BUTTON_ID);
                }
                // to resize toolbars after changes...
                coolBar.update(true);
            }
        }
    }

    private static void remove(IContributionManager manager, int itemIndex, String id) {
        IContributionItem[] items = manager.getItems();
        int controlIdx = -1;
        for (int i = 0; i < items.length; i++) {
            if (items[i].isSeparator() || items[i] instanceof ActionContributionItem
                    || items[i] instanceof CommandContributionItem) {
                controlIdx++;
                if (controlIdx == itemIndex && id.equals(items[i].getId())) {
                    IContributionItem item = manager.remove(items[i]);
                    // refresh menu gui
                    manager.update(true);
                    if (item != null) {
                        item.dispose();
                    }
                    break;
                }
            }
        }
    }

    private static boolean getPref(String prefkey) {
        IPreferenceStore store = AnyEditToolsPlugin.getDefault().getPreferenceStore();
        return store.getBoolean(prefkey);
    }

    private static int insert(IDirtyWorkaround myAction, ToolBarManager manager,
            int controlIdx) {
        IContributionItem item;
        // get "file->save" action
        item = manager.find(myAction.getId());
        if (item != null) {
            // copy references to opened editor/part
            myAction.copyStateAndDispose(item);
            if (controlIdx < 0) {
                // get/remember position
                IContributionItem[] items = manager.getItems();
                for (int i = 0; i < items.length; i++) {
                    if (items[i].isSeparator()
                            || items[i] instanceof ActionContributionItem) {
                        controlIdx++;
                        if (items[i] == item) {
                            break;
                        }
                    }
                }
            }
            // clean old one
            manager.remove(item);
            item = new ActionContributionItem(myAction);
            manager.insert(controlIdx, item);
            // refresh menu gui
            manager.update(true);
        } else if (controlIdx >= 0) {
            item = new ActionContributionItem(myAction);
            manager.insert(controlIdx, item);
            // refresh menu gui
            manager.update(true);
        }
        return controlIdx;
    }

    private static void insert(IDirtyWorkaround myAction, MenuManager menu) {
        IContributionItem item;
        String id = myAction.getId();
        // get "file->save" action
        item = menu.find(id);
        if (item != null) {
            // copy references to opened editor/part
            myAction.copyStateAndDispose(item);
            // remember position
            int controlIdx = menu.indexOf(id);
            // clean old one
            menu.remove(item);
            item = new ActionContributionItem(myAction);
            menu.insert(controlIdx, item);
            // refresh menu gui
            menu.update(true);
        }
    }
}
