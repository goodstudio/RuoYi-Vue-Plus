package org.dromara.workflow.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.dto.RoleDTO;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StreamUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.workflow.common.constant.FlowConstant;
import org.dromara.workflow.common.enums.BusinessStatusEnum;
import org.dromara.workflow.common.enums.TaskStatusEnum;
import org.dromara.workflow.domain.bo.*;
import org.dromara.workflow.domain.vo.MultiInstanceVo;
import org.dromara.workflow.domain.vo.TaskVo;
import org.dromara.workflow.flowable.cmd.AddSequenceMultiInstanceCmd;
import org.dromara.workflow.flowable.cmd.DeleteSequenceMultiInstanceCmd;
import org.dromara.workflow.flowable.cmd.UpdateBusinessStatusCmd;
import org.dromara.workflow.service.IActTaskService;
import org.dromara.workflow.utils.WorkflowUtils;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.*;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.bpmn.behavior.ParallelMultiInstanceBehavior;
import org.flowable.engine.impl.bpmn.behavior.SequentialMultiInstanceBehavior;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static org.dromara.workflow.common.constant.FlowConstant.FLOWABLE_SKIP_EXPRESSION_ENABLED;
import static org.dromara.workflow.common.constant.FlowConstant.INITIATOR;

/**
 * 任务 服务层实现
 *
 * @author may
 */
@RequiredArgsConstructor
@Service
public class ActTaskServiceImpl implements IActTaskService {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    private final ManagementService managementService;

    /**
     * 启动任务
     *
     * @param startProcessBo 启动流程参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startWorkFlow(StartProcessBo startProcessBo) {
        Map<String, Object> map = new HashMap<>();
        if (StringUtils.isBlank(startProcessBo.getBusinessKey())) {
            throw new ServiceException("启动工作流时必须包含业务ID");
        }
        // 判断当前业务是否启动过流程
        HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(startProcessBo.getBusinessKey()).processInstanceTenantId(TenantHelper.getTenantId()).singleResult();
        if (ObjectUtil.isNotEmpty(historicProcessInstance)) {
            BusinessStatusEnum.checkStartStatus(historicProcessInstance.getBusinessStatus());
        }
        TaskQuery taskQuery = taskService.createTaskQuery();
        List<Task> taskResult = taskQuery.processInstanceBusinessKey(startProcessBo.getBusinessKey()).taskTenantId(TenantHelper.getTenantId()).list();
        if (CollUtil.isNotEmpty(taskResult)) {
            map.put("processInstanceId", taskResult.get(0).getProcessInstanceId());
            map.put("taskId", taskResult.get(0).getId());
            return map;
        }
        // 设置启动人
        identityService.setAuthenticatedUserId(String.valueOf(LoginHelper.getUserId()));
        Authentication.setAuthenticatedUserId(String.valueOf(LoginHelper.getUserId()));
        // 启动流程实例（提交申请）
        Map<String, Object> variables = startProcessBo.getVariables();
        // 启动跳过表达式
        variables.put(FLOWABLE_SKIP_EXPRESSION_ENABLED, true);
        // 流程发起人
        variables.put(INITIATOR, (String.valueOf(LoginHelper.getUserId())));
        ProcessInstance pi = runtimeService.startProcessInstanceByKeyAndTenantId(startProcessBo.getProcessKey(), startProcessBo.getBusinessKey(), variables, TenantHelper.getTenantId());
        // 将流程定义名称 作为 流程实例名称
        runtimeService.setProcessInstanceName(pi.getProcessInstanceId(), pi.getProcessDefinitionName());
        // 申请人执行流程
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(pi.getId()).taskTenantId(TenantHelper.getTenantId()).list();
        if (taskList.size() > 1) {
            throw new ServiceException("请检查流程第一个环节是否为申请人！");
        }

        runtimeService.updateBusinessStatus(pi.getProcessInstanceId(), BusinessStatusEnum.DRAFT.getStatus());
        taskService.setAssignee(taskList.get(0).getId(), LoginHelper.getUserId().toString());
        taskService.setVariable(taskList.get(0).getId(), "processInstanceId", pi.getProcessInstanceId());
        map.put("processInstanceId", pi.getProcessInstanceId());
        map.put("taskId", taskList.get(0).getId());
        return map;
    }

    /**
     * 办理任务
     *
     * @param completeTaskBo 办理任务参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeTask(CompleteTaskBo completeTaskBo) {
        try {
            List<RoleDTO> roles = LoginHelper.getLoginUser().getRoles();
            String userId = String.valueOf(LoginHelper.getUserId());
            TaskQuery taskQuery = taskService.createTaskQuery();
            taskQuery.taskId(completeTaskBo.getTaskId())
                .taskTenantId(TenantHelper.getTenantId()).taskCandidateOrAssigned(userId);
            if (CollUtil.isNotEmpty(roles)) {
                List<String> groupIds = StreamUtils.toList(roles, e -> String.valueOf(e.getRoleId()));
                taskQuery.taskCandidateGroupIn(groupIds);
            }
            Task task = taskQuery.singleResult();
            if (task == null) {
                throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
            }
            if (task.isSuspended()) {
                throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
            }
            //办理委托任务
            if (ObjectUtil.isNotEmpty(task.getDelegationState()) && FlowConstant.PENDING.equals(task.getDelegationState().name())) {
                taskService.resolveTask(completeTaskBo.getTaskId());
                TaskEntity newTask = WorkflowUtils.createNewTask(task);
                taskService.addComment(newTask.getId(), task.getProcessInstanceId(), completeTaskBo.getMessage());
                taskService.complete(newTask.getId());
                return true;
            }
            runtimeService.updateBusinessStatus(task.getProcessInstanceId(), BusinessStatusEnum.WAITING.getStatus());
            //办理意见
            taskService.addComment(completeTaskBo.getTaskId(), task.getProcessInstanceId(), TaskStatusEnum.PASS.getStatus(), StringUtils.isBlank(completeTaskBo.getMessage()) ? "同意" : completeTaskBo.getMessage());
            //办理任务
            taskService.complete(completeTaskBo.getTaskId(), completeTaskBo.getVariables());
            List<Task> list = taskService.createTaskQuery().taskTenantId(TenantHelper.getTenantId()).processInstanceId(task.getProcessInstanceId()).list();
            if (CollUtil.isEmpty(list)) {
                UpdateBusinessStatusCmd updateBusinessStatusCmd = new UpdateBusinessStatusCmd(task.getProcessInstanceId(), BusinessStatusEnum.FINISH.getStatus());
                managementService.executeCommand(updateBusinessStatusCmd);
            }
            return true;
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 查询当前用户的待办任务
     *
     * @param taskBo 参数
     */
    @Override
    public TableDataInfo<TaskVo> getTaskWaitByPage(TaskBo taskBo) {
        List<RoleDTO> roles = LoginHelper.getLoginUser().getRoles();
        String userId = String.valueOf(LoginHelper.getUserId());
        TaskQuery query = taskService.createTaskQuery();
        query.taskTenantId(TenantHelper.getTenantId()).taskCandidateOrAssigned(userId);
        if (CollUtil.isNotEmpty(roles)) {
            List<String> groupIds = StreamUtils.toList(roles, e -> String.valueOf(e.getRoleId()));
            query.taskCandidateGroupIn(groupIds);
        }
        if (StringUtils.isNotBlank(taskBo.getName())) {
            query.taskNameLike("%" + taskBo.getName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionName())) {
            query.processDefinitionNameLike("%" + taskBo.getProcessDefinitionName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionKey())) {
            query.processDefinitionKey(taskBo.getProcessDefinitionKey());
        }
        List<Task> taskList = query.listPage(taskBo.getPageNum(), taskBo.getPageSize());
        List<ProcessInstance> processInstanceList = null;
        if (CollUtil.isNotEmpty(taskList)) {
            Set<String> processInstanceIds = StreamUtils.toSet(taskList, Task::getProcessInstanceId);
            processInstanceList = runtimeService.createProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        }
        List<TaskVo> list = new ArrayList<>();
        for (Task task : taskList) {
            TaskVo taskVo = BeanUtil.toBean(task, TaskVo.class);
            if (CollUtil.isNotEmpty(processInstanceList)) {
                processInstanceList.stream().filter(e -> e.getId().equals(task.getProcessInstanceId())).findFirst().ifPresent(e -> {
                    taskVo.setBusinessStatus(e.getBusinessStatus());
                    taskVo.setBusinessStatusName(BusinessStatusEnum.getEumByStatus(taskVo.getBusinessStatus()));
                    taskVo.setProcessDefinitionKey(e.getProcessDefinitionKey());
                    taskVo.setProcessDefinitionName(e.getProcessDefinitionName());
                });
            }
            taskVo.setAssignee(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            taskVo.setParticipantVo(WorkflowUtils.getCurrentTaskParticipant(task.getId()));
            taskVo.setMultiInstance(WorkflowUtils.isMultiInstance(task.getProcessDefinitionId(), task.getTaskDefinitionKey()) != null);
            list.add(taskVo);
        }
        long count = query.count();
        return new TableDataInfo<>(list, count);
    }

    /**
     * 查询当前租户所有待办任务
     *
     * @param taskBo 参数
     */
    @Override
    public TableDataInfo<TaskVo> getAllTaskWaitByPage(TaskBo taskBo) {
        TaskQuery query = taskService.createTaskQuery().taskTenantId(TenantHelper.getTenantId());
        if (StringUtils.isNotBlank(taskBo.getName())) {
            query.taskNameLike("%" + taskBo.getName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionName())) {
            query.processDefinitionNameLike("%" + taskBo.getProcessDefinitionName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionKey())) {
            query.processDefinitionKey(taskBo.getProcessDefinitionKey());
        }
        List<Task> taskList = query.listPage(taskBo.getPageNum(), taskBo.getPageSize());
        List<ProcessInstance> processInstanceList = null;
        if (CollUtil.isNotEmpty(taskList)) {
            Set<String> processInstanceIds = StreamUtils.toSet(taskList, Task::getProcessInstanceId);
            processInstanceList = runtimeService.createProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        }
        List<TaskVo> list = new ArrayList<>();
        for (Task task : taskList) {
            TaskVo taskVo = BeanUtil.toBean(task, TaskVo.class);
            if (CollUtil.isNotEmpty(processInstanceList)) {
                processInstanceList.stream().filter(e -> e.getId().equals(task.getProcessInstanceId())).findFirst().ifPresent(e -> {
                    taskVo.setBusinessStatus(e.getBusinessStatus());
                    taskVo.setBusinessStatusName(BusinessStatusEnum.getEumByStatus(taskVo.getBusinessStatus()));
                    taskVo.setProcessDefinitionKey(e.getProcessDefinitionKey());
                    taskVo.setProcessDefinitionName(e.getProcessDefinitionName());
                });
            }
            taskVo.setAssignee(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            taskVo.setParticipantVo(WorkflowUtils.getCurrentTaskParticipant(task.getId()));
            taskVo.setMultiInstance(WorkflowUtils.isMultiInstance(task.getProcessDefinitionId(), task.getTaskDefinitionKey()) != null);
            list.add(taskVo);
        }
        long count = query.count();
        return new TableDataInfo<>(list, count);
    }

    /**
     * 查询当前用户的已办任务
     *
     * @param taskBo 参数
     */
    @Override
    public TableDataInfo<TaskVo> getTaskFinishByPage(TaskBo taskBo) {
        String userId = String.valueOf(LoginHelper.getUserId());
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
            .taskAssignee(userId).taskTenantId(TenantHelper.getTenantId()).finished().orderByHistoricTaskInstanceStartTime().desc();
        if (StringUtils.isNotBlank(taskBo.getName())) {
            query.taskNameLike("%" + taskBo.getName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionName())) {
            query.processDefinitionNameLike("%" + taskBo.getProcessDefinitionName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionKey())) {
            query.processDefinitionKey(taskBo.getProcessDefinitionKey());
        }
        List<HistoricTaskInstance> taskInstanceList = query.listPage(taskBo.getPageNum(), taskBo.getPageSize());
        List<HistoricProcessInstance> historicProcessInstanceList = null;
        if (CollUtil.isNotEmpty(taskInstanceList)) {
            Set<String> processInstanceIds = StreamUtils.toSet(taskInstanceList, HistoricTaskInstance::getProcessInstanceId);
            historicProcessInstanceList = historyService.createHistoricProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        }
        List<TaskVo> list = new ArrayList<>();
        for (HistoricTaskInstance task : taskInstanceList) {
            TaskVo taskVo = BeanUtil.toBean(task, TaskVo.class);
            if (CollUtil.isNotEmpty(historicProcessInstanceList)) {
                historicProcessInstanceList.stream().filter(e -> e.getId().equals(task.getProcessInstanceId())).findFirst().ifPresent(e -> {
                    taskVo.setBusinessStatus(e.getBusinessStatus());
                    taskVo.setBusinessStatusName(BusinessStatusEnum.getEumByStatus(taskVo.getBusinessStatus()));
                    taskVo.setProcessDefinitionKey(e.getProcessDefinitionKey());
                    taskVo.setProcessDefinitionName(e.getProcessDefinitionName());
                });
            }
            taskVo.setAssignee(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            list.add(taskVo);
        }
        long count = query.count();
        return new TableDataInfo<>(list, count);
    }

    /**
     * 查询当前租户所有已办任务
     *
     * @param taskBo 参数
     */
    @Override
    public TableDataInfo<TaskVo> getAllTaskFinishByPage(TaskBo taskBo) {
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
            .taskTenantId(TenantHelper.getTenantId()).finished().orderByHistoricTaskInstanceStartTime().desc();
        if (StringUtils.isNotBlank(taskBo.getName())) {
            query.taskNameLike("%" + taskBo.getName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionName())) {
            query.processDefinitionNameLike("%" + taskBo.getProcessDefinitionName() + "%");
        }
        if (StringUtils.isNotBlank(taskBo.getProcessDefinitionKey())) {
            query.processDefinitionKey(taskBo.getProcessDefinitionKey());
        }
        List<HistoricTaskInstance> taskInstanceList = query.listPage(taskBo.getPageNum(), taskBo.getPageSize());
        List<HistoricProcessInstance> historicProcessInstanceList = null;
        if (CollUtil.isNotEmpty(taskInstanceList)) {
            Set<String> processInstanceIds = StreamUtils.toSet(taskInstanceList, HistoricTaskInstance::getProcessInstanceId);
            historicProcessInstanceList = historyService.createHistoricProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        }
        List<TaskVo> list = new ArrayList<>();
        for (HistoricTaskInstance task : taskInstanceList) {
            TaskVo taskVo = BeanUtil.toBean(task, TaskVo.class);
            if (CollUtil.isNotEmpty(historicProcessInstanceList)) {
                historicProcessInstanceList.stream().filter(e -> e.getId().equals(task.getProcessInstanceId())).findFirst().ifPresent(e -> {
                    taskVo.setBusinessStatus(e.getBusinessStatus());
                    taskVo.setBusinessStatusName(BusinessStatusEnum.getEumByStatus(taskVo.getBusinessStatus()));
                    taskVo.setProcessDefinitionKey(e.getProcessDefinitionKey());
                    taskVo.setProcessDefinitionName(e.getProcessDefinitionName());
                });
            }
            taskVo.setAssignee(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            list.add(taskVo);
        }
        long count = query.count();
        return new TableDataInfo<>(list, count);
    }

    /**
     * 委派任务
     *
     * @param delegateBo 参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delegateTask(DelegateBo delegateBo) {
        TaskEntity task = (TaskEntity) taskService.createTaskQuery()
            .taskTenantId(TenantHelper.getTenantId()).taskId(delegateBo.getTaskId())
            .taskCandidateOrAssigned(String.valueOf(LoginHelper.getUserId())).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            TaskEntity newTask = WorkflowUtils.createNewTask(task);
            taskService.addComment(newTask.getId(), task.getProcessInstanceId(), TaskStatusEnum.PENDING.getStatus(), "【" + LoginHelper.getUsername() + "】委派给【" + delegateBo.getNickName() + "】");
            //委托任务
            taskService.delegateTask(delegateBo.getTaskId(), delegateBo.getUserId());
            //办理生成的任务记录
            taskService.complete(newTask.getId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 终止任务
     *
     * @param terminationBo 参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean terminationTask(TerminationBo terminationBo) {
        Task task = taskService.createTaskQuery()
            .taskTenantId(TenantHelper.getTenantId()).taskId(terminationBo.getTaskId()).singleResult();

        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(task.getProcessInstanceId()).processInstanceTenantId(TenantHelper.getTenantId()).singleResult();
        if (ObjectUtil.isNotEmpty(historicProcessInstance)) {
            BusinessStatusEnum.checkStatus(historicProcessInstance.getBusinessStatus());
        }
        try {
            if (StringUtils.isBlank(terminationBo.getComment())) {
                terminationBo.setComment(LoginHelper.getUsername() + "终止了申请");
            } else {
                terminationBo.setComment(LoginHelper.getUsername() + "终止了申请：" + terminationBo.getComment());
            }
            taskService.addComment(task.getId(), task.getProcessInstanceId(), TaskStatusEnum.TERMINATION.getStatus(), terminationBo.getComment());
            List<Task> list = taskService.createTaskQuery().taskTenantId(TenantHelper.getTenantId())
                .processInstanceId(task.getProcessInstanceId()).list();
            if (CollectionUtil.isNotEmpty(list)) {
                List<Task> subTasks = StreamUtils.filter(list, e -> StringUtils.isNotBlank(e.getParentTaskId()));
                if (CollectionUtil.isNotEmpty(subTasks)) {
                    subTasks.forEach(e -> taskService.deleteTask(e.getId()));
                }
                runtimeService.deleteProcessInstance(task.getProcessInstanceId(), StrUtil.EMPTY);
            }
            runtimeService.updateBusinessStatus(task.getProcessInstanceId(), BusinessStatusEnum.TERMINATION.getStatus());
            return true;
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 转办任务
     *
     * @param transmitBo 参数
     */
    @Override
    public boolean transferTask(TransmitBo transmitBo) {
        Task task = taskService.createTaskQuery().taskId(transmitBo.getTaskId()).taskTenantId(TenantHelper.getTenantId())
            .taskCandidateOrAssigned(String.valueOf(LoginHelper.getUserId())).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            TaskEntity newTask = WorkflowUtils.createNewTask(task);
            taskService.addComment(newTask.getId(), task.getProcessInstanceId(), TaskStatusEnum.TRANSFER.getStatus(),
                StringUtils.isNotBlank(transmitBo.getComment()) ? transmitBo.getComment() : LoginHelper.getUsername() + "转办了任务");
            taskService.complete(newTask.getId());
            taskService.setAssignee(task.getId(), transmitBo.getUserId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 会签任务加签
     *
     * @param addMultiBo 参数
     */
    @Override
    public boolean addMultiInstanceExecution(AddMultiBo addMultiBo) {
        Task task = taskService.createTaskQuery().taskId(addMultiBo.getTaskId()).taskTenantId(TenantHelper.getTenantId())
            .taskCandidateOrAssigned(String.valueOf(LoginHelper.getUserId())).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();
        String processDefinitionId = task.getProcessDefinitionId();

        try {
            MultiInstanceVo multiInstanceVo = WorkflowUtils.isMultiInstance(processDefinitionId, taskDefinitionKey);
            if (multiInstanceVo == null) {
                throw new ServiceException("当前环节不是会签节点");
            }
            if (multiInstanceVo.getType() instanceof ParallelMultiInstanceBehavior) {
                for (Long assignee : addMultiBo.getAssignees()) {
                    runtimeService.addMultiInstanceExecution(taskDefinitionKey, processInstanceId, Collections.singletonMap(multiInstanceVo.getAssignee(), assignee));
                }
            } else if (multiInstanceVo.getType() instanceof SequentialMultiInstanceBehavior) {
                AddSequenceMultiInstanceCmd addSequenceMultiInstanceCmd = new AddSequenceMultiInstanceCmd(task.getExecutionId(), multiInstanceVo.getAssigneeList(), addMultiBo.getAssignees());
                managementService.executeCommand(addSequenceMultiInstanceCmd);
            }
            List<String> assigneeNames = addMultiBo.getAssigneeNames();
            String username = LoginHelper.getUsername();
            TaskEntity newTask = WorkflowUtils.createNewTask(task);
            taskService.addComment(newTask.getId(), processInstanceId, TaskStatusEnum.SIGN.getStatus(), username + "加签【" + String.join(StringUtils.SEPARATOR, assigneeNames) + "】");
            taskService.complete(newTask.getId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 会签任务减签
     *
     * @param deleteMultiBo 参数
     */
    @Override
    public boolean deleteMultiInstanceExecution(DeleteMultiBo deleteMultiBo) {
        Task task = taskService.createTaskQuery().taskId(deleteMultiBo.getTaskId()).taskTenantId(TenantHelper.getTenantId())
            .taskCandidateOrAssigned(String.valueOf(LoginHelper.getUserId())).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();
        String processDefinitionId = task.getProcessDefinitionId();
        try {
            MultiInstanceVo multiInstanceVo = WorkflowUtils.isMultiInstance(processDefinitionId, taskDefinitionKey);
            if (multiInstanceVo == null) {
                throw new ServiceException("当前环节不是会签节点");
            }
            if (multiInstanceVo.getType() instanceof ParallelMultiInstanceBehavior) {
                for (String executionId : deleteMultiBo.getExecutionIds()) {
                    runtimeService.deleteMultiInstanceExecution(executionId, false);
                }
                for (String taskId : deleteMultiBo.getTaskIds()) {
                    historyService.deleteHistoricTaskInstance(taskId);
                }
            } else if (multiInstanceVo.getType() instanceof SequentialMultiInstanceBehavior) {
                DeleteSequenceMultiInstanceCmd deleteSequenceMultiInstanceCmd = new DeleteSequenceMultiInstanceCmd(task.getAssignee(), task.getExecutionId(), multiInstanceVo.getAssigneeList(), deleteMultiBo.getAssigneeIds());
                managementService.executeCommand(deleteSequenceMultiInstanceCmd);
            }
            List<String> assigneeNames = deleteMultiBo.getAssigneeNames();
            String username = LoginHelper.getUsername();
            TaskEntity newTask = WorkflowUtils.createNewTask(task);
            taskService.addComment(newTask.getId(), processInstanceId, TaskStatusEnum.SIGN_OFF.getStatus(), username + "减签【" + String.join(StringUtils.SEPARATOR, assigneeNames) + "】");
            taskService.complete(newTask.getId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 驳回审批
     *
     * @param backProcessBo 参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String backProcess(BackProcessBo backProcessBo) {
        Task task = taskService.createTaskQuery().taskId(backProcessBo.getTaskId()).taskTenantId(TenantHelper.getTenantId())
            .taskAssignee(String.valueOf(LoginHelper.getUserId())).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            String processInstanceId = task.getProcessInstanceId();
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();
            //校验单据
            BusinessStatusEnum.checkStatus(processInstance.getBusinessStatus());
            //判断是否有多个任务
            List<Task> taskList = taskService.createTaskQuery().processInstanceId(processInstanceId).taskTenantId(TenantHelper.getTenantId()).list();
            //申请人节点
            HistoricTaskInstance historicTaskInstance = historyService.createHistoricTaskInstanceQuery().finished().orderByHistoricTaskInstanceEndTime().asc().list().get(0);
            String backTaskDefinitionKey = historicTaskInstance.getTaskDefinitionKey();
            taskService.addComment(task.getId(), processInstanceId, TaskStatusEnum.BACK.getStatus(), StringUtils.isNotBlank(backProcessBo.getMessage()) ? backProcessBo.getMessage() : "退回");
            if (taskList.size() > 1) {
                //当前多个任务驳回到单个节点
                runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                    .moveActivityIdsToSingleActivityId(taskList.stream().map(Task::getTaskDefinitionKey).distinct().collect(Collectors.toList()), backTaskDefinitionKey)
                    .changeState();
            } else {
                //当前单个节点驳回单个节点
                runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                    .moveActivityIdTo(task.getTaskDefinitionKey(), backTaskDefinitionKey)
                    .changeState();
            }
            List<Task> list = taskService.createTaskQuery().processInstanceId(processInstanceId).taskTenantId(TenantHelper.getTenantId()).list();
            for (Task t : list) {
                taskService.setAssignee(t.getId(), historicTaskInstance.getAssignee());
            }
            runtimeService.updateBusinessStatus(processInstanceId, BusinessStatusEnum.BACK.getStatus());
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
        return task.getProcessInstanceId();
    }
}
