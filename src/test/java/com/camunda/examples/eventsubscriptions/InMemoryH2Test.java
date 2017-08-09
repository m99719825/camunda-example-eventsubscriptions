package com.camunda.examples.eventsubscriptions;

import static org.camunda.bpm.engine.test.assertions.ProcessEngineAssertions.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineAssertions.init;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.execute;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.job;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.runtimeService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.logging.LogFactory;
import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.EventSubscriptionQuery;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.runtime.VariableInstanceQuery;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.TestCoverageProcessEngineRuleBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test case starting an in-memory database-backed Process Engine.
 */
public class InMemoryH2Test {

	@ClassRule
	@Rule
	public static ProcessEngineRule rule = TestCoverageProcessEngineRuleBuilder.create().build();

	private static final String PROCESS_DEFINITION_KEY = "queryingeventsubscriptions";
	
	/*
	 * Constant values from process.bpmn file
	 */
	private static final String TIMEREVENT_30SEC_PASSED = "TimerEvent_30SecPassed";
	private static final String RECEIVE_TASK = "ReceiveTask_WaitForMessage";
	private static final String CORRELATION_MESSAGE = "my_message";
	

	static {
		LogFactory.useSlf4jLogging(); // MyBatis
	}

	@Before
	public void setup() {
		init(rule.getProcessEngine());
	}

	/**
	 * Just tests if the process definition is deployable.
	 */
	@Test
	@Deployment(resources = "process.bpmn")
	public void testParsingAndDeployment() {
		// nothing is done here, as we just want to check for exceptions during deployment
	}

	/**
	 * This test case demonstrates how to check whether a message can be correlated trivially, meaning without using any
	 * correlation keys, etc. This is also an entire happy path test.
	 */
	@Test
	@Deployment(resources = "process.bpmn")
	public void testEventSubscriptionQueryingForSingleInstance() {

		EventSubscriptionQuery subscriptionQuery;
		List<EventSubscription> eventSubscriptions = new ArrayList<EventSubscription>();

		/*
		 * We need to start an instance of our process first.
		 */
		RuntimeService runtimeService = runtimeService();
		ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);

		/*
		 * The process instance reaches its first wait state at the 30 seconds timer event...
		 */
		assertThat(processInstance).isStarted();
		assertThat(processInstance).isWaitingAt(TIMEREVENT_30SEC_PASSED);

		/*
		 * ... which does not create an event subscription of type message. That is why the query below does not return
		 * any subscription.
		 */
		subscriptionQuery = runtimeService.createEventSubscriptionQuery()
				.eventName(CORRELATION_MESSAGE)
				.eventType("message");
		eventSubscriptions = subscriptionQuery.list();

		Assert.assertEquals("waiting for a time to be passed shall not create a event subscription", 0,
				eventSubscriptions.size());

		execute(job());

		assertThat(processInstance).isWaitingAt(RECEIVE_TASK);

		/*
		 * However, as soon as the token arrived at the ReceiveTask the same query results in one subscription.
		 */
		subscriptionQuery = runtimeService.createEventSubscriptionQuery()
				.eventName(CORRELATION_MESSAGE)
				.eventType("message");
		eventSubscriptions = subscriptionQuery.list();

		Assert.assertEquals("waiting for a message shall create exactly 1 event subscription", 1,
				eventSubscriptions.size());

		/*
		 * This means that we are safe to use correlateMessage without any further correlations keys now.
		 */
		runtimeService.correlateMessage(CORRELATION_MESSAGE);

		assertThat(processInstance).isEnded();
	}

	/**
	 * This test case demonstrates how to check whether a set of information intended to correlate a message with is
	 * sufficient to match exactly one subscription.
	 */
	@Test
	@Deployment(resources = "process.bpmn")
	public void testEventSubscriptionQueryingForMultipleRunningInstances() {

		final String A_CORRELATION_KEY = "A_CORRELATION_KEY";
		final String INSTANCE_1 = "INSTANCE_1";
		final String INSTANCE_2 = "INSTANCE_2";

		EventSubscriptionQuery subscriptionQuery;
		List<EventSubscription> eventSubscriptions = new ArrayList<EventSubscription>();

		RuntimeService runtimeService = runtimeService();

		/*
		 * As we need two instances of mutual process definition that hold different variables. We place the token rigth
		 * in front of the Receive Task.
		 */
		ProcessInstance processInstance1 = runtimeService.createProcessInstanceByKey(PROCESS_DEFINITION_KEY)
				.setVariable(A_CORRELATION_KEY, INSTANCE_1).startAfterActivity(TIMEREVENT_30SEC_PASSED).execute();
		ProcessInstance processInstance2 = runtimeService.createProcessInstanceByKey(PROCESS_DEFINITION_KEY)
				.setVariable(A_CORRELATION_KEY, INSTANCE_2).startAfterActivity(TIMEREVENT_30SEC_PASSED).execute();

		/*
		 * Due to the fact that two process instances are waiting to receive a message of the same name each, now, we
		 * cannot correlate by this message name only anymore.
		 */
		try {
			runtimeService.correlateMessage(CORRELATION_MESSAGE);
			Assert.fail("correlating by message name only shall fail when more than one instance awaits a message");
		} catch (MismatchingMessageCorrelationException e) {
			// do nothing
		}

		/*
		 * Instead of running into the RuntimeException above which makes the senders transaction to roll back, we can
		 * use Event Subscription Queries to find out how many subscriptions are waiting for a specific correlation
		 */
		subscriptionQuery = runtimeService.createEventSubscriptionQuery()
				.eventName(CORRELATION_MESSAGE)
				.eventType("message");
		eventSubscriptions = subscriptionQuery.list();

		/*
		 * As we created two process instances above this query will result in two subscriptions waiting. At that point
		 * this means that we cannot correlate using event resp. message name only.
		 */
		Assert.assertEquals("querying for message subscriptions by message name shall return collection of two", 2,
				eventSubscriptions.size());

		/*
		 * ... But we can use the result to conduct a more precise query because it contains the process instance ids a
		 * certain subscriptions belong to.
		 */
		List<String> processInstanceIds = new ArrayList<String>();
		for (EventSubscription eventSubscription : eventSubscriptions) {
			String processInstanceId = eventSubscription.getProcessInstanceId();
			processInstanceIds.add(processInstanceId);
		}

		/*
		 * These process instance ids can be used to query for several other entities, e.g. VariableInstances. Knowing
		 * that our two process instances differ in the value of process variable A_CORRELATION_KEY we can use a
		 * VaribleInstaceQuery to check all process instances that are subscribed to the above event for a that value.
		 * This query eventually results in a single result.
		 */
		VariableInstanceQuery variableInstanceQuery = runtimeService.createVariableInstanceQuery()
				.processInstanceIdIn(processInstanceIds.toArray(new String[processInstanceIds.size()]))
				.variableValueEquals(A_CORRELATION_KEY, INSTANCE_1);
		List<VariableInstance> variableInstances = variableInstanceQuery.list();

		Assert.assertEquals(1, variableInstances.size());

		/*
		 * We found out that we are able to correlate our message using the message name in combination with a
		 * correlation key.
		 */
		Map<String, Object> correlationKeys = new HashMap<String, Object>();
		correlationKeys.put(A_CORRELATION_KEY, INSTANCE_1);
		try {
			runtimeService.correlateMessage(CORRELATION_MESSAGE, correlationKeys);
		} catch (MismatchingMessageCorrelationException e) {
			Assert.fail("correlation by message name and correlation keys shall not throw exception.");
		}

		assertThat(processInstance1).isEnded();
		assertThat(processInstance2).isWaitingAt(RECEIVE_TASK);
	}

}
