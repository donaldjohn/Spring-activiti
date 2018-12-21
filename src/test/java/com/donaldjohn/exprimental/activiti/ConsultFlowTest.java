package com.donaldjohn.exprimental.activiti;

import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricProcessInstanceQuery;
import org.activiti.engine.impl.RepositoryServiceImpl;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.impl.pvm.PvmTransition;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.engine.test.ActivitiRule;
import org.activiti.image.impl.DefaultProcessDiagramGenerator;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {"classpath:conf/spring-mybatis.xml", "classpath:conf/spring-activiti.xml"})
public class ConsultFlowTest
{

    @Value("${consult.task.expire.duration}")
    private String empTaskExpireDurationBeforeAccept;


    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    @Rule
    public ActivitiRule activitiSpringRule;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RepositoryService repositoryService;

    @Test
    public void processStartTest()
    {
        startTaskTest();

        managerAssignTest();


        empTaskHandleTest();
    }


    @Test
    public void clearAllRuningTest()
    {
        List<ProcessInstance> processInstances = runtimeService.createProcessInstanceQuery().processDefinitionKey("consultTask").list();
        for (ProcessInstance instance : processInstances)
        {
            runtimeService.deleteProcessInstance(instance.getProcessInstanceId(), "");
        }


        List<HistoricProcessInstance> historicProcessInstanceList = historyService.createHistoricProcessInstanceQuery().processDefinitionKey("consultTask").list();

        for (HistoricProcessInstance historicProcessInstance : historicProcessInstanceList)
        {
            historyService.deleteHistoricProcessInstance(historicProcessInstance.getId());
        }
    }


    @Test
    public void startTaskTest()
    {
        clearAllRuningTest();

        //启动流程
        for (int i = 0; i < 30; i++)
        {
            Map<String, Object> startVariables = new HashMap<>();
            startVariables.put("duration", empTaskExpireDurationBeforeAccept);

            ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("consultTask", "00" + i, startVariables);

            //不同的发起人
            identityService.setAuthenticatedUserId("user" + i % 6);

            System.out.println("Started Process [ID:" + processInstance.getId() + "] [" + processInstance.getName() + "] [" + processInstance.getBusinessKey() + "]");
        }


        printPrefixedMessage("模拟用户启动流程统计: ");
        for (int i = 0; i < 6; i++)
        {
            List<ProcessInstance> processOfUser0 = runtimeService.createProcessInstanceQuery().variableValueEquals("userId", "user" + i).list();
            System.out.println(" user" + i + " has started " + processOfUser0.size() + " processes!");
            System.out.println("They are : ");

            for (int j = 0; j < processOfUser0.size(); j++)
            {
                ProcessInstance processInstance = processOfUser0.get(j);
                System.out.println("[pID: " + processInstance.getId() + "][pName: " + processInstance.getName() + "][bKey: " + processInstance.getBusinessKey() + "] [uId: " + processInstance.getProcessVariables().get("userId") + "]");
            }
        }

        printTaskRuntimeStatistics();
    }


    @Test
    public void managerAssignTest()
    {
        List<Task> allTasksOfAdminGroup = taskService.createTaskQuery().processDefinitionKey("consultTask").or().taskAssignee("manager1").taskCandidateGroup("admin").endOr().list();

        System.out.println("The count of all tasks of AdminGroup is " + allTasksOfAdminGroup.size());

        printTaskListDetail(allTasksOfAdminGroup);

        for (Task task : allTasksOfAdminGroup)
        {
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessDefinitionId()).singleResult();
            runtimeService.setVariable(task.getExecutionId(), "duration", empTaskExpireDurationBeforeAccept);
        }


        System.out.println("=================开始管理员admin分配或拒绝任务");
        for (int i = 0; i < allTasksOfAdminGroup.size(); i++)
        {
            Task currentTask = allTasksOfAdminGroup.get(i);

            Map<String, Object> processVariables = currentTask.getProcessVariables();


            //选择当前任务
            if (StringUtils.isBlank(currentTask.getAssignee()))
                taskService.claim(currentTask.getId(), "manager" + i % 2);


            Map<String, Object> adminVariableMap = new HashMap<>();

            printTaskRuntimeStatistics();

            if (0 == i % 3)
            {
                adminVariableMap.put("rejectTask", "true");//拒绝任务
                taskService.addComment(currentTask.getId(), currentTask.getProcessInstanceId(), "对不起, 您的提交为垃圾提交，请尊重我们的平台，共建和谐社会！");
                printPrefixedMessage("拒绝任务:");
                printTaskDetail(currentTask);
            }
            else
            {
                adminVariableMap.put("rejectTask", "false");//拒绝任务
                adminVariableMap.put("employeeId", "emp" + i % 3);//分配任务
                printPrefixedMessage("分配:");
                printTaskDetail(currentTask);
            }

            taskService.complete(currentTask.getId(), adminVariableMap);//完成任务
        }

        System.out.println("=================分配完毕");
        //printTaskRuntimeStatistics();
        printAllTaskDetail();
    }


    @Test
    public void empTaskHandleTest()
    {
        printTaskRuntimeStatistics();
        //处理前两个emp的任务, 第三个emp测试超时功能
        for (int j = 0; j < 2; j++)
        {
            String empId = "emp" + j;
            printPrefixedMessage("==========================开始处理EMP 专家 " + empId + "的任务");
            printTaskRuntimeStatistics();

            List<Task> taskList = taskService.createTaskQuery().processDefinitionKey("consultTask").taskAssignee(empId).list();

            System.out.println(empId + "共有 " + taskList.size() + "个任务!");

            for (int k = 0; k < taskList.size(); k++)
            {
                Task currentTaskOfEmp = taskList.get(k);
                Map<String, Object> employeeHandleMap = new HashMap<>();

                //推回1/3的任务
                if (k % 3 == 0)
                {
                    printPrefixedMessage("专家任务回退: ");
                    printTaskDetail(currentTaskOfEmp);
                    employeeHandleMap.put("returnTask", "true");
                    taskService.addComment(currentTaskOfEmp.getId(), currentTaskOfEmp.getProcessInstanceId(), "对不起, 我不方便，请找其他人进行处理！");
                }
                else
                {
                    //专家处理任务
                    printPrefixedMessage("专家任务处理: ");
                    printTaskDetail(currentTaskOfEmp);
                    employeeHandleMap.put("returnTask", "false");
                }

                taskService.complete(currentTaskOfEmp.getId(), employeeHandleMap);
            }

            printPrefixedMessage(empId + "的任务处理结束");
            printTaskRuntimeStatistics();
        }

        printAllTaskDetail();
    }

    @Test
    public void timeEventTest()
    {
        printAllTaskDetail();

        try
        {
            Thread.sleep(1000 * 60);
            printPrefixedMessage("超时后的任务回退测试:");

            printTaskRuntimeStatistics();
        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }

        printAllTaskDetail();
    }


    @Test
    public void printAllTaskDetail()
    {
        printTaskListDetail(taskService.createTaskQuery().processDefinitionKey("consultTask").list());
    }


    @Test
    public void historyTest()
    {
        List<HistoricProcessInstance> historicProcessInstanceList = historyService.createHistoricProcessInstanceQuery().processDefinitionKey("consultTask").orderByProcessInstanceBusinessKey().asc().list();

        for (HistoricProcessInstance historicProcessInstance : historicProcessInstanceList)
        {
            System.out.println("============流程信息: ");
            System.out.println("[Id:" + historicProcessInstance.getId() + "]");
            System.out.println("[Name:" + historicProcessInstance.getName() + "]");
            System.out.println("[ProcessDefinitionId:" + historicProcessInstance.getProcessDefinitionId() + "]");
            System.out.println("[ProcessDefinitionKey:" + historicProcessInstance.getProcessDefinitionKey() + "]");
            System.out.println("[ProcessDefinitionName:" + historicProcessInstance.getProcessDefinitionName() + "]");
            System.out.println("[BusinessKey:" + historicProcessInstance.getBusinessKey() + "]");
            System.out.println("[StartUserId:" + historicProcessInstance.getStartUserId() + "]");
            System.out.println("[StartTime:" + historicProcessInstance.getStartTime() + "]");
            System.out.println("[EndTime:" + historicProcessInstance.getEndTime() + "]");
            System.out.println("[DurationInMillis:" + historicProcessInstance.getDurationInMillis() / 1000 + "]");
            System.out.println("[StartActivityId:" + historicProcessInstance.getStartActivityId() + "]");
            System.out.println("[EndActivityId:" + historicProcessInstance.getEndActivityId() + "]");
            System.out.println("[ProcessVariables:" + historicProcessInstance.getProcessVariables() + "]");


            System.out.println("++++++++++流程的活动信息: ");
            List<HistoricActivityInstance> historicActivityInstances = historyService.createHistoricActivityInstanceQuery().processInstanceId(historicProcessInstance.getId()).orderByHistoricActivityInstanceStartTime().asc().list();

            for (HistoricActivityInstance historicActivityInstance : historicActivityInstances)
            {
                System.out.println("[Id:" + historicActivityInstance.getId() + "]");
                System.out.println("[ActivityId:" + historicActivityInstance.getActivityId() + "]");
                System.out.println("[ActivityName:" + historicActivityInstance.getActivityName() + "]");
                System.out.println("[ActivityType:" + historicActivityInstance.getActivityType() + "]");
                System.out.println("[ExecutionId:" + historicActivityInstance.getExecutionId() + "]");
                System.out.println("[TaskId:" + historicActivityInstance.getTaskId() + "]");
                System.out.println("[Assignee:" + historicActivityInstance.getAssignee() + "]");
                System.out.println("[StartTime:" + historicActivityInstance.getStartTime() + "]");
                System.out.println("[EndTime:" + historicActivityInstance.getEndTime() + "]");
                System.out.println("[DurationInMillis:" + historicActivityInstance.getDurationInMillis() / 1000 + "]");
                System.out.println("[ProcessVariables:" + historicProcessInstance.getProcessVariables() + "]");
            }

            List<String> historyActivityIds = historicActivityInstances.stream().map(HistoricActivityInstance::getActivityId).collect(Collectors.toList());


            //Generate Flow Diagram
            File outPNGFile = new File(historicProcessInstance.getId() + "-" + historicProcessInstance.getName() + ".png");

            BpmnModel bpmnmodel = repositoryService.getBpmnModel(historicProcessInstance.getProcessDefinitionId());


            InputStream in = repositoryService.getProcessDiagram(historicProcessInstance.getProcessDefinitionId());

            try
            {
                OutputStream out = new FileOutputStream(outPNGFile);
                IOUtils.copy(in, out);
            } catch (IOException e)
            {
                e.printStackTrace();
            }


        }
    }


    private void printPrefixedMessage(String message)
    {
        System.out.println("=================" + message);
    }

    public List<String> getHighLightedFlows(
            ProcessDefinitionEntity processDefinitionEntity,
            List<HistoricActivityInstance> historicActivityInstances)
    {
        List<String> highFlows = new ArrayList<String>();// 用以保存高亮的线flowId

        // 对历史流程节点进行遍历
        for (int i = 0; i < historicActivityInstances.size(); i++)
        {
            ActivityImpl activityImpl = processDefinitionEntity
                    .findActivity(historicActivityInstances.get(i)
                            .getActivityId());// 得 到节点定义的详细信息

            List<ActivityImpl> sameStartTimeNodes = new ArrayList<ActivityImpl>();// 用以保存后续开始时间相同的节点

            if ((i + 1) >= historicActivityInstances.size())
            {
                break;
            }

            ActivityImpl sameActivityImpl1 = processDefinitionEntity
                    .findActivity(historicActivityInstances.get(i + 1)
                            .getActivityId());// 将后面第一个节点放在时间相同节点的集合里

            sameStartTimeNodes.add(sameActivityImpl1);

            for (int j = i + 1; j < historicActivityInstances.size() - 1; j++)
            {
                HistoricActivityInstance activityImpl1 = historicActivityInstances
                        .get(j);// 后续第一个节点

                HistoricActivityInstance activityImpl2 = historicActivityInstances
                        .get(j + 1);// 后续第二个节点

                if (activityImpl1.getStartTime().equals(
                        activityImpl2.getStartTime()))
                {
                    // 如果第一个节点和第二个节点开始时间相同保存
                    ActivityImpl sameActivityImpl2 = processDefinitionEntity
                            .findActivity(activityImpl2.getActivityId());

                    sameStartTimeNodes.add(sameActivityImpl2);

                }
                else
                {// 有不相同跳出循环
                    break;
                }
            }


            List<PvmTransition> pvmTransitions = activityImpl
                    .getOutgoingTransitions();// 取出节点的所有出去的线


            for (PvmTransition pvmTransition : pvmTransitions)
            {// 对所有的线进行遍历

                ActivityImpl pvmActivityImpl = (ActivityImpl) pvmTransition
                        .getDestination();// 如果取出的线的目标节点存在时间相同的节点里，保存该线的id，进行高亮显示

                if (sameStartTimeNodes.contains(pvmActivityImpl))
                {
                    highFlows.add(pvmTransition.getId());
                }
            }
        }
        return highFlows;
    }


    @Test
    public void printTaskRuntimeStatistics()
    {

        System.out.println("******************************Runtime统计开始****************************************");
        HistoricProcessInstanceQuery processQuery = historyService.createHistoricProcessInstanceQuery().processDefinitionKey("consultTask").finished();
        System.out.println("当前结束: " + processQuery.count() + "个流程, 其中");

        for (int i = 0; i < 6; i++)
        {
            System.out.println("已结束的 User" + i + " 启动的 " + processQuery.startedBy("user" + i).count() + "个");
        }


        /*TaskQuery taskQuery = taskService.createTaskQuery().processDefinitionKey("consultTask");*/

        System.out.println("当前有: " + taskService.createTaskQuery().processDefinitionKey("consultTask").count() + "个任务,  " + taskService.createTaskQuery().processDefinitionKey("consultTask").count() + "个管理员任务" + taskService.createTaskQuery().processDefinitionKey("consultTask").taskName("employeeHandleTask").count() + "个专家任务");

        System.out.println("Admin管理员组有 " + taskService.createTaskQuery().processDefinitionKey("consultTask").taskCandidateGroup("admin").count() + "个任务");

        for (int i = 0; i < 6; i++)
        {
            System.out.println("运行中的 User" + i + " 启动的 " + runtimeService.createProcessInstanceQuery().involvedUser("user" + i).count() + "个");
        }

        System.out.println("emp0有" + taskService.createTaskQuery().processDefinitionKey("consultTask").taskAssignee("emp0").count() + "个任务");
        System.out.println("emp1有" + taskService.createTaskQuery().processDefinitionKey("consultTask").taskAssignee("emp1").count() + "个任务");
        System.out.println("emp2有" + taskService.createTaskQuery().processDefinitionKey("consultTask").taskAssignee("emp2").count() + "个任务");

        System.out.println("******************************Runtime统计结束****************************************");
    }


    private void printTaskListDetail(List<Task> tasks)
    {
        for (Task task : tasks)
        {
            printTaskDetail(task);
        }
    }


    private void printTaskDetail(Task task)
    {
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();


        Map<String, Object> processVariables = runtimeService.getVariables(task.getExecutionId());

        System.out.println(" Task Detail [Id: " + task.getId() + "][Key: " + task.getTaskDefinitionKey() + "][Name: " + task.getName() + "] [ExecuteId: " + task.getExecutionId() + "] [assignee:  " + task.getAssignee() + "] [bKy:" + processInstance.getBusinessKey() + "] [duration: " + processVariables.get("duration") + "] [createTime: " + task.getCreateTime() + "] [pDKey: " + processInstance.getProcessDefinitionKey() + "] [pID: " + processInstance.getProcessDefinitionId() + "]");
    }

}
