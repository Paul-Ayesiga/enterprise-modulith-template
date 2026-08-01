/**
 * Customer support: tickets, messages, per-priority SLAs, assignment, and SLA-breach escalation.
 * A tenant opens and converses on its own org's tickets; platform-support works the cross-tenant
 * queue, assigns, and adds public replies or internal notes (never shown to the tenant). A ShedLock
 * job flags tickets past their SLA due, bumps priority, counts the breach, notifies the queue, and
 * publishes {@code TicketEscalated} (fanned out as {@code org.ticket.escalated}). A public reply
 * notifies the opener in-app — the {@code JobCompleted} notifier pattern.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Support")
package ug.co.smsone.support;
