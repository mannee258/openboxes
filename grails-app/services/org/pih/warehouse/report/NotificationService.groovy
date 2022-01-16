/**
* Copyright (c) 2012 Partners In Health.  All rights reserved.
* The use and distribution terms for this software are covered by the
* Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
* which can be found in the file epl-v10.html at the root of this distribution.
* By using this software in any fashion, you are agreeing to be bound by
* the terms of this license.
* You must not remove this notice, or any other, from this software.
**/
package org.pih.warehouse.report

import org.apache.commons.mail.EmailException
import org.apache.commons.validator.EmailValidator
import org.codehaus.groovy.grails.commons.ApplicationHolder
import org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib
import org.codehaus.groovy.grails.plugins.web.taglib.RenderTagLib
import org.codehaus.groovy.grails.web.context.ServletContextHolder
import org.codehaus.groovy.grails.web.errors.GrailsWrappedRuntimeException
import org.pih.warehouse.api.PartialReceipt
import org.pih.warehouse.auth.AuthService
import org.pih.warehouse.core.Event
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.MailService
import org.pih.warehouse.core.Person
import org.pih.warehouse.core.RoleType
import org.pih.warehouse.core.User
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.shipping.Shipment
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.WebApplicationContextUtils

class NotificationService {

    def dataService
    def userService
    MailService mailService
    def grailsApplication
    def messageSource

    boolean transactional = false

    def renderTemplate(String template, Map model) {
        // Hack to ensure that the GSP template engine has access to a request.
        // FIXME Need to fix this when we migrate to grails 3
        def webRequest = RequestContextHolder.getRequestAttributes()
        if(!webRequest) {
            def servletContext = ServletContextHolder.getServletContext()
            def applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext)
            grails.util.GrailsWebUtil.bindMockWebRequest(applicationContext)
        }
        return new RenderTagLib().render(template: template, model: model)
    }

    def getExpiryAlertsByLocation(Location location, Integer daysUntilExpiry = 0) {
        String query = """
            select * 
            from product_inventory_expiry_view 
            where days_until_expiry <= ${daysUntilExpiry} 
            and location_id = '${location.id}'
            and quantity_on_hand > 0
            order by days_until_expiry asc
            """
        return dataService.executeQuery(query)
    }

    def getStockAlertsByLocation(Location location) {
        String query = """
            select * 
            from product_inventory_compare_view 
            where location_id = '${location.id}'
            order by product_name asc
            """
        return dataService.executeQuery(query)
    }

    def sendExpiryAlerts(Location location, Integer daysUntilExpiry = 60, List<RoleType> roleTypes, Boolean skipOnEmpty) {
        def subject = "Expiry Alerts - ${location.name}"
        def expiryAlerts = getExpiryAlertsByLocation(location, daysUntilExpiry)
        if (expiryAlerts.isEmpty() && skipOnEmpty) {
            log.info "Skipped ${subject} email for location ${location} because there are no alerts"
            return
        }
        def subscribers = userService.findUsersByRoleTypes(location, roleTypes)
        def csv = dataService.generateCsv(expiryAlerts)
        def expired = expiryAlerts.findAll { it.days_until_expiry <= 0 }
        def expiring = expiryAlerts.findAll { it.days_until_expiry > 0 }
        def model = [location: location, expiring: expiring, expired: expired, daysUntilExpiry: daysUntilExpiry]
        log.info "Sending ${expiryAlerts.size()} ${subject} alerts and ${subscribers.size()} subscribers for location ${location}"
        sendAlerts(subject, "/email/expiryAlerts", model, subscribers, csv)
    }

    def sendStockAlerts(Location location, String status, List<RoleType> roleTypes, Boolean skipOnEmpty) {
        def subject = "Stock Alerts - Status ${status} - ${location.name}"
        def stockAlerts = getStockAlertsByLocation(location)
        def products = stockAlerts.findAll { it.status == status }
        if (products.isEmpty() && skipOnEmpty) {
            log.info "Skipped ${subject} email for location ${location} because there are no alerts"
            return
        }
        def subscribers = userService.findUsersByRoleTypes(location, roleTypes)
        def model = [location: location, status: status, products: products]
        def csv = dataService.generateCsv(products)
        log.info "Sending ${products.size()} ${subject} alerts and ${subscribers.size()} subscribers for location ${location} "
        sendAlerts(subject, "/email/stockAlerts", model, subscribers, csv)
    }

    def sendAlerts(String subject, String template, Map model, List<User> subscribers, String csv) {

        Collection toList = subscribers.collect { it.email }.findAll{ it != null }.toArray()
        if (toList.isEmpty()) {
            log.info("Skipped ${subject} email because there are no subscribers")
            return
        }

        String body = renderTemplate(template, model)

        // Send email with attachment (if csv exists)
        if (csv) {
            mailService.sendHtmlMailWithAttachment(toList, [], subject, body, csv.bytes, "${subject}.csv", "text/csv")
        }
        else {
            mailService.sendHtmlMail(subject, body, toList)
        }
    }

    def sendRequisitionRejectedNotifications(String requestNumber, String message, List<RoleType> roleTypes) {
        Location location = AuthService.getCurrentLocation().get()
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${requestNumber} has been rejected"
        String template = "/email/requisitionRejected"
        Map model = [requestNumber:requestNumber, message:message]
        sendNotifications(model, users, template, subject)
    }

    def sendRequisitionRevisedNotifications(Requisition requisition, Location location, List<RoleType> roleTypes) {
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${requisition?.requestNumber} has been revised"
        String template = "/email/requisitionRevised"
        Map model = [requisition: requisition]
        sendNotifications(model, users, template, subject)
    }


    def sendRequisitionUpdatedNotification(Requisition requisition, Location location, List<RoleType> roleTypes) {
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${requisition?.requestNumber} has been updated"
        String template = "/email/requisitionUpdated"
        sendRequisitionNotifications(requisition, users, template, subject)
    }

    def sendRequisitionStatusNotification(Requisition requisition, Location location, List<RoleType> roleTypes) {
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${requisition?.requestNumber} status has been changed to ${requisition?.status}"
        String template = "/email/requisitionStatusChanged"
        sendRequisitionNotifications(requisition, users, template, subject)
    }

    def sendShipmentCreatedNotification(Shipment shipmentInstance, Location location, List<RoleType> roleTypes) {
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${shipmentInstance?.shipmentNumber} has been created"
        String template = "/email/shipmentCreated"
        sendShipmentNotifications(shipmentInstance, users, template, subject)
    }

    def sendShipmentAcceptedNotification(Shipment shipmentInstance, Location location, List<RoleType> roleTypes) {
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${shipmentInstance?.shipmentNumber} has been accepted"
        String template = "/email/shipmentAccepted"
        sendShipmentNotifications(shipmentInstance, users, template, subject)
    }

    def sendShipmentIssuedNotification(Shipment shipmentInstance, Location location, List<RoleType> roleTypes) {
        def users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${shipmentInstance?.shipmentNumber} has been shipped"
        String template = "/email/shipmentShipped"
        sendShipmentNotifications(shipmentInstance, users, template, subject)
    }

    def sendShipmentReceiptNotification(Shipment shipmentInstance, Location location, List<RoleType> roleTypes) {
        List<User> users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${shipmentInstance?.shipmentNumber} has been received"
        String template = "/email/shipmentReceived"
        sendShipmentNotifications(shipmentInstance, users, template, subject)
    }

    def sendShipmentDeliveryNotification(Shipment shipmentInstance, Location location, List<RoleType> roleTypes) {
        List<User> users = userService.findUsersByRoleTypes(location, roleTypes)
        String subject = "Order ${shipmentInstance?.shipmentNumber} has been delivered"
        String template = "/email/shipmentDelivered"
        sendShipmentNotifications(shipmentInstance, users, template, subject)
    }

    def sendShipmentStatusNotification(Shipment shipment, Event event, Location location, List<RoleType> roleTypes) {
        try {
            def users = userService.findUsersByRoleTypes(location, roleTypes)
            String subject = "Order ${shipment?.shipmentNumber} status has been changed to ${event?.eventType?.name}"
            String template = "/email/shipmentStatusChanged"
            sendShipmentNotifications(shipment, users, template, subject)
        } catch (Exception e) {
            log.error ("Unable to send shipment status notification " + e.message, e)
        }
    }

    def sendShipmentItemsShippedNotification(Shipment shipmentInstance) {
        def emailValidator = EmailValidator.getInstance()
        def g = grailsApplication.mainContext.getBean('org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib')
        def recipientItems = shipmentInstance.shipmentItems.groupBy {it.recipient }
        recipientItems.each { Person recipient, items ->
            if (emailValidator.isValid(recipient?.email)) {
                def subject = g.message(code: "email.yourItemShipped.message", args: [shipmentInstance.origin.name, shipmentInstance.destination.name, shipmentInstance.shipmentNumber])
                def body = "${g.render(template: "/email/shipmentItemShipped", model: [shipmentInstance: shipmentInstance, shipmentItems: items, recipient:recipient])}"
                mailService.sendHtmlMail(subject, body.toString(), recipient.email)
            }
        }
    }

    def sendRequisitionNotifications(Requisition requisition, List<User> users, String template, String subject) {
        sendNotifications([requisition:requisition], users, template, subject)
    }

    def sendNotifications(Map model, List<User> users, String template, String subject) {
        try {
            String body = renderTemplate(template, model)
            List emails = users.collect { it.email }.findAll { it }
            if (!emails.empty) {
                mailService.sendHtmlMail(subject, body, emails)
            }
        } catch (Exception e) {
            log.error("Unable to send requisition notifications " + e.message, e)
        }
    }


    def sendShipmentNotifications(Shipment shipmentInstance, List<User> users, String template, String subject) {
        try {
            String body = renderTemplate(template, [shipmentInstance: shipmentInstance])
            List emails = users.collect { it.email }
            if (!emails.empty) {
                mailService.sendHtmlMail(subject, body, emails)
            }
        } catch (Exception e) {
            log.error("Unable to send shipment notifications " + e.message, e)
        }
    }

    def sendReceiptNotifications(PartialReceipt partialReceipt) {
        Shipment shipment = partialReceipt?.shipment
        def emailValidator = EmailValidator.getInstance()
        def g = grailsApplication.mainContext.getBean('org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib')
        def recipientItems = partialReceipt.partialReceiptItems.groupBy {it.recipient }
        recipientItems.each { Person recipient, items ->
            if (emailValidator.isValid(recipient?.email)) {
                def subject = g.message(code: "email.yourItemReceived.message", args: [shipment.destination.name, shipment.shipmentNumber])
                def body = "${g.render(template: "/email/shipmentItemReceived", model: [shipmentInstance: shipment, receiptItems: items, recipient: recipient, receivedBy: partialReceipt.recipient])}"
                mailService.sendHtmlMail(subject, body.toString(), recipient.email)
            }
        }
    }

    def sendApplicationErrorNotification(Location location, Exception exception) {
        log.info "Sending application error notification"
        if (location.active && location.supports(org.pih.warehouse.core.ActivityCode.ENABLE_NOTIFICATIONS)) {
            List<RoleType> roleTypes = [RoleType.ROLE_ERROR_NOTIFICATION]
            List subscribers = userService.findUsersByRoleTypes(location, roleTypes)
            List emails = subscribers.collect { it.email }

            GrailsWrappedRuntimeException grailsException = new GrailsWrappedRuntimeException(ServletContextHolder.servletContext, exception)
            String body = renderTemplate("/email/applicationError",
                    [exception: grailsException, location: location])
            mailService.sendHtmlMail("Application Error: ${exception?.message}", body, emails)
        }
        else {
            log.warn("Unable to send notification because location ${location.name} is inactive or has not enabled notifications")
        }
    }

    def sendUserAccountCreation(User userInstance, Map additionalQuestions) {
        try {
            // Send email to user notification recipients
            def recipients = userService.findUsersByRoleType(RoleType.ROLE_USER_NOTIFICATION)
            if (recipients) {
                def locale = new Locale(grailsApplication.config.openboxes.locale.defaultLocale)
                def to = recipients?.collect { it.email }?.unique()
                def subject = messageSource.getMessage('email.userAccountCreated.message', [userInstance.username].toArray(), locale)
                def body = renderTemplate("/email/userAccountCreated", [userInstance: userInstance, additionalQuestions: additionalQuestions])
                mailService.sendHtmlMail(subject, body.toString(), to)
            }

        } catch (EmailException e) {
            log.error("Unable to send creation email: " + e.message, e)
        }
    }

    def sendUserAccountConfirmation(User userInstance, Map additionalQuestions) {
        try {
            // Send confirmation email to user
            if (userInstance?.email) {
                def locale = userInstance?.locale ?: new Locale(grailsApplication.config.openboxes.locale.defaultLocale)
                def subject = messageSource.getMessage('email.userAccountConfirmed.message', [userInstance?.email].toArray(), locale)
                def body = renderTemplate("/email/userAccountConfirmed", [userInstance: userInstance, additionalQuestions: additionalQuestions])
                mailService.sendHtmlMail(subject, body.toString(), userInstance?.email)
            }
        } catch (EmailException e) {
            log.error("Unable to send confirmation email: " + e.message, e)
        }
    }

}
