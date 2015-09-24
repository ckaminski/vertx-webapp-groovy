package com.darthcoder.webapp

import io.vertx.groovy.core.buffer.Buffer
import io.vertx.groovy.ext.mail.MailClient

class Emailer { 
    private _vertx
    private mailClient
    private mailConfig

    public Emailer(vertx, config) { 
        _vertx = vertx
        mailConfig = config
        mailClient = MailClient.createNonShared(vertx, config)
    }

    /*public sendEmail(message, handler ) {
        mailClient?.sendMail(message, { result ->
            if ( handler != null ) { 
                handler()
            } else { 
                if (result.succeeded()) {
                    println(result.result())
                } else {
                    result.cause().printStackTrace()
                }
            }
        }) 
    }
    */

    public void sendMessage(msgInfo, handler) {
       def message = [:]
        message.from = msgInfo?.from
        message.to   = msgInfo?.to
        message.cc   = msgInfo?.cc
        message.text = msgInfo?.body
        message.html = msgInfo?.htmlBody

       mailClient?.sendMail(message, { result ->
         if ( handler != null ) {
             handler()
         } else {
             if (!result.succeeded()) {
                 result.cause().printStackTrace()
             } else {
                 println(result.result())
             }
         }
       })
    }
}

