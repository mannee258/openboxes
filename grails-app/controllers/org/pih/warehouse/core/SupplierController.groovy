/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.core

import grails.converters.JSON
import org.pih.warehouse.importer.CSVUtils
import org.pih.warehouse.product.Product

class SupplierController {

    def locationService
    def documentService
    def orderService

    def list = {
        params.max = Math.min(params.max ? params.int('max') : 10, 100)
        params.offset = params.offset ? params.int("offset") : 0

        def suppliers = locationService.getSuppliers(params.q, params.max, params.offset as int)

        [suppliers: suppliers, suppliersTotal: suppliers.totalCount]
    }

    def show = {
        Organization supplier = Organization.get(params.id)
        List<Document> documents = documentService.getAllDocumentsBySupplierOrganization(supplier)

        [supplier: supplier, documents: documents]
    }

    def getPriceHistory = {
        Organization supplier = Organization.get(params.supplierId)
        Product product = Product.get(params.productId)
        def data = orderService.getOrderItemsForPriceHistory(supplier, product, params.q)

        if (params.format == "text/csv") {
            def csv = CSVUtils.getCSVPrinter()
            csv.printRecord(
                "Order Number",
                "Date Created",
                "Description",
                "Product Code",
                "Product",
                "Source Code",
                "Supplier Code",
                "Manufacturer",
                "Manufacturer Code",
                "Unit Price"
            )

            data.each {
                csv.printRecord(
                        it?.orderNumber,
                        it?.dateCreated.format("MM/dd/yyyy"),
                        it?.description,
                        it?.productCode,
                        it?.productName,
                        it?.sourceCode,
                        it?.supplierCode,
                        it?.manufacturerName,
                        it?.manufacturerCode,
                        it?.unitPrice ? CSVUtils.formatCurrency(
                                number: (it?.unitPrice ?: 0) / (it?.quantityPerUom ?: 1),
                                currencyCode: it?.currencyCode,
                                isUnitPrice: true) : ''
                )
            }

            response.setHeader("Content-disposition", "attachment; filename=\"Price-History.csv\"")
            render(contentType: "text/csv", text: csv.out.toString())
            return
        }

        render([aaData: data] as JSON)
    }

}
