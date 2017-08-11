# Querying Event Subscriptions or Hardening Message Correlation

## Introduction
### TL;DR
This process application contains unit tests that demonstrate how to check if a process instance is "ready-to-receive" a message before actually cerrelate it. Just have a look into `InMemoryH2Test`.

And run `mvn clean package` to see all tests suceeding.

### Why Should I Check For An Individual Receiver Before Correlation?
In general, there is no strict requirement to check whether a message correlation attempt is going to succeed or to fail. However, the BPMN standard requires a message to be delivered to exactly 1 (ONE) receiver. In case no one is waiting for the message it just will pop out.

The Camunda Engine implements messaging through a subscription based approach. As soon as the token of a process instance arrives at a receive event or receive task a subscription for the message is created. As an effect of that, multiple process instances (receivers) can wait for the same message at the same time. To prevent this unstandardized behaviour `RuntimeService.correlateMessage()` throws a
`MismatchingMessageCorrelationException` if not exactly one process instance is ready-to-receive. More precisely, this exception is thrown if no one is waiting or if more than one is waiting for a certain message.

The `MismatchingMessageCorrelationException` is a RuntimeException. Depending on your environment this might set a running transaction to rollback, which might cause trouble. Hence sometime you want to check beforehand if a process instance is ready-to-receive a message.

## A Deeper Look


The easiest possibility is to query for existing subscriptions:
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

Often you want to identity the waiting process instance by process variables containing business data or correlation key. If you want to do so you have to use a different query:

```java
runtimeService.createExecutionQuery() 
        .messageEventSubscription()
        .processVariableValueEquals("correlationId", correlationId)
        .list();
```



