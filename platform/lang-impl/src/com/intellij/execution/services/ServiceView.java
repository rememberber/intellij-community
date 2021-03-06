// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.services;

import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationStatus;
import com.intellij.execution.dashboard.tree.RunDashboardGrouper;
import com.intellij.execution.dashboard.tree.RunDashboardStatusFilter;
import com.intellij.execution.dashboard.tree.RunDashboardTreeMouseListener;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DataManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.TreeExpander;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.ide.util.treeView.NodeRenderer;
import com.intellij.ide.util.treeView.smartTree.ActionPresentation;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.ui.*;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.tree.BaseTreeModel;
import com.intellij.ui.tree.TreeVisitor;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FactoryMap;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeModelAdapter;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.List;
import java.util.*;

import static com.intellij.execution.dashboard.RunDashboardRunConfigurationStatus.*;
import static com.intellij.ui.AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED;
import static com.intellij.util.ui.UIUtil.CONTRAST_BORDER_COLOR;

class ServiceView extends JPanel implements Disposable {
  private static final ExtensionPointName<ServiceViewContributor> EP_NAME =
    ExtensionPointName.create("com.intellij.serviceViewContributor");

  @NonNls private static final String PLACE_TOOLBAR = "RunDashboardContent#Toolbar";
  @NonNls private static final String SERVICE_VIEW_NODE_TOOLBAR = "ServiceViewNodeToolbar";
  @NonNls private static final String SERVICE_VIEW_NODE_POPUP = "ServiceViewNodePopup";
  @NonNls private static final String SERVICE_VIEW_TREE_TOOLBAR = "ServiceViewTreeToolbar";

  private final Splitter mySplitter;
  private final JPanel myTreePanel;
  private final Tree myTree;
  private final JPanel myDetailsPanel;
  private final JBPanelWithEmptyText myMessagePanel;
  private final JComponent myToolbar;

  private final Map<Object, ServiceViewContributor.ViewDescriptor> myViewDescriptors = new HashMap<>();
  private final Map<Object, ServiceViewContributor> myContributors = new HashMap<>();
  private final Map<ServiceViewContributor, ServiceViewContributor.ViewDescriptorRenderer> myRenderers = new HashMap<>();
  private final Map<Object, ServiceViewContributor.SubtreeDescriptor> mySubtrees = new HashMap<>();

  private final MyTreeModel myTreeModel;
  private Object myLastSelection;
  private final Set<Object> myCollapsedTreeNodeValues = new HashSet<>();
  private final List<? extends RunDashboardGrouper> myGroupers;
  private final RunDashboardStatusFilter myStatusFilter = new RunDashboardStatusFilter();

  @NotNull private final Project myProject;

  private final RecursionGuard myGuard = RecursionManager.createGuard("ServiceView.getData");

  ServiceView(@NotNull Project project, @NotNull List<? extends RunDashboardGrouper> groupers) {
    super(new BorderLayout());
    myProject = project;
    myGroupers = groupers;

    myTree = new Tree();
    myTreeModel = new MyTreeModel();
    myTree.setModel(myTreeModel);
    initTree();
    myTreeModel.refreshAll();

    mySplitter = new OnePixelSplitter(false, 0.3f);
    myTreePanel = new JPanel(new BorderLayout());
    myTreePanel.add(ScrollPaneFactory.createScrollPane(myTree, SideBorder.LEFT), BorderLayout.CENTER);
    mySplitter.setFirstComponent(myTreePanel);
    myDetailsPanel = new JPanel(new BorderLayout());
    myMessagePanel = new JBPanelWithEmptyText().withEmptyText("Select service in tree to view details");
    myDetailsPanel.add(myMessagePanel, BorderLayout.CENTER);
    mySplitter.setSecondComponent(myDetailsPanel);
    add(mySplitter, BorderLayout.CENTER);

    myToolbar = createToolbar();
    add(myToolbar, BorderLayout.WEST);
    JComponent treeToolbar = createTreeToolBar();
    myTreePanel.add(treeToolbar, BorderLayout.NORTH);

    putClientProperty(DataManager.CLIENT_PROPERTY_DATA_PROVIDER, (DataProvider)dataId -> {
      if (PlatformDataKeys.HELP_ID.is(dataId)) {
        return ServiceViewManagerImpl.getToolWindowContextHelpId();
      }
      else if (PlatformDataKeys.SELECTED_ITEMS.is(dataId)) {
        return TreeUtil.collectSelectedUserObjects(myTree).toArray();
      }
      ServiceViewContributor.ViewDescriptor descriptor = getSelectedDescriptor();
      DataProvider dataProvider = descriptor == null ? null : descriptor.getDataProvider();
      if (dataProvider != null) {
        return myGuard.doPreventingRecursion(this, false, () -> dataProvider.getData(dataId));
      }
      return null;
    });
  }

  @Nullable
  private ServiceViewContributor.ViewDescriptor getSelectedDescriptor() {
    return myViewDescriptors.get(getSelectedObject());
  }

  @Nullable
  private Object getSelectedObject() {
    TreePath path = myTree.getSelectionPath();
    return TreeUtil.getLastUserObject(path);
  }

  private void initTree() {
    // look
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    myTree.setLineStyleAngled();
    myTree.setCellRenderer(new MyTreeCellRenderer());
    UIUtil.putClientProperty(myTree, ANIMATION_IN_RENDERER_ALLOWED, true);

    // listeners
    myTree.addTreeSelectionListener(e -> onSelectionChanged());
    myTree.addTreeExpansionListener(new TreeExpansionListener() {
      @Override
      public void treeExpanded(TreeExpansionEvent event) {
        Object value = TreeUtil.getLastUserObject(event.getPath());
        if (value != null) {
          myCollapsedTreeNodeValues.remove(value);
        }
      }

      @Override
      public void treeCollapsed(TreeExpansionEvent event) {
        Object value = TreeUtil.getLastUserObject(event.getPath());
        if (value != null) {
          myCollapsedTreeNodeValues.add(value);
        }
      }
    });
    new TreeSpeedSearch(myTree, TreeSpeedSearch.NODE_DESCRIPTOR_TOSTRING, true);
    RunDashboardTreeMouseListener mouseListener = new RunDashboardTreeMouseListener(myTree);
    mouseListener.installOn(myTree);

    myTreeModel.addTreeModelListener(new TreeModelAdapter() {
      @Override
      public void treeStructureChanged(TreeModelEvent event) {
        TreeUtil.promiseVisit(myTree, new TreeVisitor() {
          @NotNull
          @Override
          public Action visit(@NotNull TreePath path) {
            Object node = TreeUtil.getLastUserObject(path);
            if (node instanceof NodeDescriptor) {
              ((NodeDescriptor)node).update();
            }
            return Action.CONTINUE;
          }
        });
      }
    });
    //RowsDnDSupport.install(myTree, myTreeModel);
    //new DoubleClickListener() {
    //  @Override
    //  protected boolean onDoubleClick(MouseEvent event) {
    //  handle double click for leaves
    //    return false;
    //  }
    //}.installOn(myTree);

    // popup
    ActionGroup actions = (ActionGroup)ActionManager.getInstance().getAction(SERVICE_VIEW_NODE_POPUP);
    PopupHandler.installPopupHandler(myTree, actions, ActionPlaces.SERVICES_POPUP, ActionManager.getInstance());
  }

  private void setTreeVisible(boolean visible) {
    myTreePanel.setVisible(visible);
    myToolbar.setBorder(visible ? null : BorderFactory.createMatteBorder(0, 0, 0, 1, CONTRAST_BORDER_COLOR));
  }

  private void onSelectionChanged() {
    List<Object> selected = TreeUtil.collectSelectedUserObjects(myTree);
    Object newSelection = ContainerUtil.getOnlyItem(selected);
    if (Comparing.equal(newSelection, myLastSelection)) return;

    ServiceViewContributor.ViewDescriptor oldDescriptor = myLastSelection == null ? null : myViewDescriptors.get(myLastSelection);
    if (oldDescriptor != null) {
      oldDescriptor.onNodeUnselected();
    }

    myLastSelection = newSelection;
    ServiceViewContributor.ViewDescriptor newDescriptor = newSelection == null ? null : myViewDescriptors.get(newSelection);

    if (newDescriptor != null) {
      newDescriptor.onNodeSelected();
    }
    Component component = newDescriptor == null ? null : newDescriptor.getContentComponent();
    Component newDetails = component == null ? myMessagePanel : component;
    if (newDetails.getParent() == myDetailsPanel) return;

    myDetailsPanel.removeAll();
    myDetailsPanel.add(newDetails, BorderLayout.CENTER);
    myDetailsPanel.revalidate();
    myDetailsPanel.repaint();
  }

  private JComponent createToolbar() {
    ActionGroup actions = (ActionGroup)ActionManager.getInstance().getAction(SERVICE_VIEW_NODE_TOOLBAR);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(PLACE_TOOLBAR, actions, false);
    toolbar.setTargetComponent(this);
    return toolbar.getComponent();
  }

  private JComponent createTreeToolBar() {
    JPanel toolBarPanel = new JPanel(new BorderLayout());
    toolBarPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 0, CONTRAST_BORDER_COLOR));

    DefaultActionGroup treeGroup = new DefaultActionGroup();

    TreeExpander treeExpander = new MyTreeExpander();
    AnAction expandAllAction = CommonActionsManager.getInstance().createExpandAllAction(treeExpander, this);
    treeGroup.add(expandAllAction);

    AnAction collapseAllAction = CommonActionsManager.getInstance().createCollapseAllAction(treeExpander, this);
    treeGroup.add(collapseAllAction);

    treeGroup.addSeparator();
    List<RunDashboardGrouper> groupers =
      ContainerUtil.filter(myGroupers, grouper -> !grouper.getRule().isAlwaysEnabled());
    if (!groupers.isEmpty()) {
      treeGroup.add(new GroupByActionGroup(groupers));
    }
    treeGroup.add(new StatusActionGroup());

    treeGroup.addSeparator();
    AnAction treeActions = ActionManager.getInstance().getAction(SERVICE_VIEW_TREE_TOOLBAR);
    treeActions.registerCustomShortcutSet(this, null);
    treeGroup.add(treeActions);

    ActionToolbar treeActionsToolBar = ActionManager.getInstance().createActionToolbar(PLACE_TOOLBAR, treeGroup, true);
    toolBarPanel.add(treeActionsToolBar.getComponent(), BorderLayout.CENTER);
    treeActionsToolBar.setTargetComponent(myTree);

    return toolBarPanel;
  }

  @Override
  public void dispose() {
  }

  void updateContent(boolean withStructure) {
    myTreeModel.refreshAll();
  }

  public void selectNode(Object node) {
    TreeUtil.select(myTree, new NodeSelectionVisitor(node), path -> {
    });
  }

  float getContentProportion() {
    return mySplitter.getProportion();
  }

  void setContentProportion(float proportion) {
    mySplitter.setProportion(proportion);
  }

  private class MyTreeExpander extends DefaultTreeExpander {
    private boolean myFlat;

    MyTreeExpander() {
      super(myTree);
      myTreeModel.addTreeModelListener(new TreeModelAdapter() {
        @Override
        public void treeStructureChanged(TreeModelEvent e) {
          myFlat = isFlat();
        }
      });
    }

    @Override
    public boolean canExpand() {
      return super.canExpand() && !myFlat;
    }

    @Override
    public boolean canCollapse() {
      return super.canCollapse() && !myFlat;
    }

    private boolean isFlat() {
      Object root = myTreeModel.getRoot();
      for (Object child : myTreeModel.getChildren(root)) {
        if (myTreeModel.getChildCount(child) > 0) {
          return false;
        }
      }
      return true;
    }
  }

  private class GroupByActionGroup extends DefaultActionGroup implements CheckedActionGroup {
    GroupByActionGroup(List<? extends RunDashboardGrouper> groupers) {
      super(ExecutionBundle.message("run.dashboard.group.by.action.name"), true);
      getTemplatePresentation().setIcon(AllIcons.Actions.GroupBy);

      for (RunDashboardGrouper grouper : groupers) {
        add(new GroupAction(grouper));
      }
    }
  }

  private class GroupAction extends ToggleAction implements DumbAware {
    private final RunDashboardGrouper myGrouper;

    GroupAction(RunDashboardGrouper grouper) {
      super();
      myGrouper = grouper;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      Presentation presentation = e.getPresentation();
      presentation.setEnabled(myTreePanel.isVisible());
      ActionPresentation actionPresentation = myGrouper.getRule().getPresentation();
      presentation.setText(actionPresentation.getText());
      presentation.setDescription(actionPresentation.getDescription());
      if (PLACE_TOOLBAR.equals(e.getPlace())) {
        presentation.setIcon(actionPresentation.getIcon());
      }
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return myGrouper.isEnabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      myGrouper.setEnabled(state);
      updateContent(true);
    }
  }

  private class StatusActionGroup extends DefaultActionGroup implements CheckedActionGroup {
    StatusActionGroup() {
      super(ExecutionBundle.message("run.dashboard.filter.by.status.action.name"), true);
      getTemplatePresentation().setIcon(AllIcons.General.Filter);

      for (final RunDashboardRunConfigurationStatus status : new RunDashboardRunConfigurationStatus[]{STARTED, FAILED, STOPPED,
        CONFIGURED}) {
        add(new ToggleAction(status.getName()) {
          @Override
          public boolean isSelected(@NotNull AnActionEvent e) {
            return myStatusFilter.isVisible(status);
          }

          @Override
          public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (state) {
              myStatusFilter.show(status);
            }
            else {
              myStatusFilter.hide(status);
            }
            updateContent(true);
          }
        });
      }
    }
  }


  private class MyTreeModel extends BaseTreeModel<Object> {
    final Object myRoot = ObjectUtils.sentinel("services root");
    Map<Object, List<Object>> myCachedChildren = FactoryMap.create(this::doGetChildren);

    @Override
    public List<?> getChildren(Object parent) {
      return myCachedChildren.get(parent);
    }

    List<Object> doGetChildren(Object parent) {
      List<Object> result;
      if (parent == myRoot) {
        result = new ArrayList<>();
        for (@SuppressWarnings("unchecked") ServiceViewContributor<Object, Object, Object> contributor : EP_NAME.getExtensions()) {
          for (Object node : contributor.getNodes(myProject)) {
            myViewDescriptors.put(node, contributor.getNodeDescriptor(node));
            mySubtrees.put(node, contributor.getNodeSubtree(node));
            myContributors.put(node, contributor);
            List<Object> groups = contributor.getGroups(node);
            for (Object group : groups) {
              myViewDescriptors.put(group, contributor.getGroupDescriptor(group));
            }
            result.add(node);
          }
        }
      }
      else {
        //noinspection unchecked
        ServiceViewContributor.SubtreeDescriptor<Object> subtree = mySubtrees.get(parent);
        if (subtree != null) {
          result = new SmartList<>();
          for (Object item : subtree.getItems()) {
            myViewDescriptors.put(item, subtree.getItemDescriptor(item));
            mySubtrees.put(item, subtree.getNodeSubtree(item));
            myContributors.put(item, myContributors.get(parent));
            result.add(item);
          }
        }
        else {
          result = Collections.emptyList();
        }
      }
      return result;
    }

    @Override
    public Object getRoot() {
      return myRoot;
    }

    void refreshAll() {
      List<Object> selected = TreeUtil.collectSelectedUserObjects(myTree);
      myCachedChildren.clear();
      treeStructureChanged(new TreePath(myRoot), null, null);
      TreeUtil.promiseSelect(myTree, selected.stream().map(NodeSelectionVisitor::new));
    }
  }

  private class MyTreeCellRenderer implements TreeCellRenderer {
    private final ColoredTreeCellRenderer myColoredRender = new NodeRenderer() {
      @Nullable
      @Override
      protected ItemPresentation getPresentation(Object node) {
        return myViewDescriptors.get(node).getPresentation();
      }
    };
    private final ServiceViewContributor.ViewDescriptorRenderer myNodeRenderer =
      (parent, value, viewDescriptor, selected, hasFocus) -> myColoredRender.getTreeCellRendererComponent(
        (JTree)parent, value, selected, true, true, 0, hasFocus);

    @Override
    public Component getTreeCellRendererComponent(JTree tree,
                                                  Object value,
                                                  boolean selected,
                                                  boolean expanded,
                                                  boolean leaf,
                                                  int row,
                                                  boolean hasFocus) {
      if (value == myTreeModel.getRoot()) {
        return myColoredRender;
      }
      ServiceViewContributor.ViewDescriptor nodeDescriptor = myViewDescriptors.get(value);
      ServiceViewContributor contributor = myContributors.get(value);
      ServiceViewContributor.ViewDescriptorRenderer renderer = myRenderers.get(contributor);
      if (renderer == null) {
        renderer = ObjectUtils.notNull(contributor.getViewDescriptorRenderer(), myNodeRenderer);
        myRenderers.put(contributor, renderer);
      }
      Component component = renderer.getRendererComponent(myTree, value, nodeDescriptor, selected, hasFocus);
      if (component == null) {
        component = myNodeRenderer.getRendererComponent(myTree, value, nodeDescriptor, selected, hasFocus);
      }
      return component;
    }
  }

  private static class NodeSelectionVisitor implements TreeVisitor {
    private final Object myNode;

    NodeSelectionVisitor(Object node) {
      myNode = node;
    }

    @NotNull
    @Override
    public Action visit(@NotNull TreePath path) {
      if (path.getLastPathComponent().equals(myNode)) return Action.INTERRUPT;

      return Action.CONTINUE;
    }
  }

  @NotNull
  private static AnAction[] doGetActions(@Nullable AnActionEvent e, boolean toolbar) {
    if (e == null) return AnAction.EMPTY_ARRAY;
    Project project = e.getProject();
    if (project == null) return AnAction.EMPTY_ARRAY;

    ServiceView serviceView = ServiceViewManagerImpl.getServiceView(project);
    if (serviceView == null || serviceView.myLastSelection == null) return AnAction.EMPTY_ARRAY;

    ServiceViewContributor.ViewDescriptor descriptor = serviceView.myViewDescriptors.get(serviceView.myLastSelection);
    ActionGroup group = toolbar ? descriptor.getToolbarActions() : descriptor.getPopupActions();
    return group == null ? AnAction.EMPTY_ARRAY : group.getChildren(e);
  }

  public static class ItemToolbarActionGroup extends ActionGroup {
    @NotNull
    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
      return doGetActions(e, true);
    }
  }

  public static class ItemPopupActionGroup extends ActionGroup {
    @NotNull
    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
      return doGetActions(e, false);
    }
  }
}
