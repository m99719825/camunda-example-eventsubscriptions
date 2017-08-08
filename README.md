# Querying Event Subscriptions or Hardening Message Correlation

## Introduction
### TL;DR
This process application contains unit tests that demonstrate how to check if a message correlation will run through before actually conducting the correlation. Just have a look into `InMemoryH2Test`.

And run `mvn clean package` to see all tests suceeding.

### Why Should I Check For An Individual Receiver Before Correlation?
In general, there is no strict requirement to check whether a message correlation attempt is going to succeed or to fail. However, the BPMN standard requires a message to be delivered to exactly 1 (ONE) receiver. In case no one is waiting for the message it just will pop out.

The Camunda Engine though implements messaging through a subscription based approach. As soon as the token of a process instance arrives at a receive event or receive task the instance subscribes the message defined with this event resp. task. As an effect of that, multiple receivers can wait for the same message at the same time. The requirement of the BPMN standard seems to be broken.

To prevent this unstandardized behaviour each of the methods of `RuntimeService.correlateMessage()` throws a
`MismatchingMessageCorrelationException` if not exactly 1 receiver for the message to correlate could be identified. More precisely, this exception is thrown if no one is waiting for a certain message or if more than one receiver is waiting for a certain message.

The down-side of `MismatchingMessageCorrelationException` is that it implements RuntimeException which cannot be caught and that it sets a transaction to rollback. The latter especially makes it impracticable for some use cases to fire message correlations in the dark hoping to match exactly one receiver.

Let's find a way to check for properly subscribed receivers before conducting critical message correlations.

## A Deeper Look
### Checking If Any One Is There
As explained above correlating a message that no one is waiting for causes an uncatchable `MismatchingMessageCorrelationException` to be thrown. So let's check for a subscription to the message before eventually sending it out.

```java
SubscriptionQuery subscriptionQuery = runtimeService.createEventSubscriptionQuery()
				.eventName("my_message")
				.eventType("message");
List<EventSubscription> eventSubscriptions = subscriptionQuery.list();
```
The listing above shows how a query on event subscriptions is used. The example code queries for subscriptions that match the following requirements:
1. Event Type is `message`
1. Message Name is `my_message`

If the List `eventSubscriptions` contains exactly one element only one subscription to the message `my_message` was found and the correlation is going to work.

```java
if (eventSubscriptions.size() == 1) {
  runtimeService.correlateMessage("my_message");
} else {
  log.warn("correlation not possible at the moment");
}
```

### Bringing Correlation Keys Into Play
In case the above query finds more than one subscription the message `my_message` would have to be correlated to several receivers. As this is not possible, again a `MismatchingMessageCorrelationException` is thrown. That's why Camunda Engine provides the approach of correlation keys that specify waiting receivers more accurately. The more correlation keys are provided the higher the chance to match the last remaining matching subscription.

Unfortunately, `EventSubscriptionQuery` exclusively allows to set query parameters that are related to event subscriptions directly, e.g. event name, event type, process instance ID, etc., but explicitly no correlation keys.

But wait! If the ID of process instances can be used as query parameter it might be provided by query results as well. And that's exactly the case.

Therefore we can improve the second listing from above. Assuming the size of list `eventSubscriptions` is greater than one we could iterate the list to fetch the ID of each process instance that has subscribed to `my_message`.

```java
List<String> processInstanceIds = new ArrayList<String>();

for (EventSubscription eventSubscription : eventSubscriptions) {
  String processInstanceId = eventSubscription.getProcessInstanceId();
	processInstanceIds.add(processInstanceId);
}
```

The initial reflex on the circumstance to have process instance IDs is to query for the actual entities and, afterwards, iteratively check these to contain certain correlation keys. But there is a more elegant approach, the `VariableInstanceQuery`. This one is capable of querying powerfully for correlation keys a.k.a. process variables. Luckily, these queries provide a parameter to specify process instance IDs.

```java
VariableInstanceQuery variableInstanceQuery = runtimeService.createVariableInstanceQuery()
				.processInstanceIdIn(processInstanceIds.toArray(new String[processInstanceIds.size()]))
				.variableValueEquals("aCorrelationKey", "a value used for correlation");
List<VariableInstance> variableInstances = variableInstanceQuery.list();
```

The code above queries for a process variable "aCorrelationKey" with the value "a value used for correlation". Furthermore the variable needs to be defined in one of the process instances referenced and provided by `processInstanceIds`. If we find only one `VariableInstance` matching these criteria we also found a combination of message name and correlation key that works for correlation.

```java
if (variableInstances.size() == 1) {
  Map<String, Object> correlationKeys = new HashMap<String, Object>();
  correlationKeys.put("aCorrelationKey", "a value used for correlation");
  runtimeService.correlateMessage("my_message", correlationKeys);
} else {
  log.warn("correlation not possible at the moment");
}
```
