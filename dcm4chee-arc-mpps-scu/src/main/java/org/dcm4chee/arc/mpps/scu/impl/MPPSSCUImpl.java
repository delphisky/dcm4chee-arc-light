/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.mpps.scu.impl;

import org.dcm4che3.conf.api.IApplicationEntityCache;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4chee.arc.conf.ArchiveAEExtension;
import org.dcm4chee.arc.qmgt.QueueManager;
import org.dcm4chee.arc.mpps.MPPSContext;
import org.dcm4chee.arc.mpps.scu.MPPSSCU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.jms.ObjectMessage;
import java.io.IOException;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Sep 2015
 */
@ApplicationScoped
class MPPSSCUImpl implements MPPSSCU {

    private static final Logger LOG = LoggerFactory.getLogger(MPPSSCUImpl.class);

    @Inject
    private QueueManager queueManager;

    @Inject
    private Device device;

    @Inject
    private IApplicationEntityCache aeCache;

    void onMPPSReceive(@Observes MPPSContext ctx) {
        ApplicationEntity ae = ctx.getLocalApplicationEntity();
        ArchiveAEExtension arcAE = ctx.getArchiveAEExtension();
        String iuid = ctx.getSopInstanceUID();
        Attributes attrs = ctx.getAttributes();
        for (String remoteAET : arcAE.mppsForwardDestinations())
            scheduleForwardMPPS(ae.getAETitle(), remoteAET, iuid, attrs);
    }

    private void scheduleForwardMPPS(String localAET, String remoteAET, String iuid, Attributes attrs) {
        try {
            ObjectMessage msg = queueManager.createObjectMessage(attrs);
            msg.setStringProperty("LocalAET", localAET);
            msg.setStringProperty("RemoteAET", remoteAET);
            msg.setStringProperty("SOPInstanceUID", iuid);
            queueManager.scheduleMessage(QUEUE_NAME, msg);
        } catch (Exception e) {
            LOG.warn("Failed to Schedule Forward of MPPS[uid={}] to AE: {}", iuid, remoteAET, e);
        }
    }

    @Override
    public void forwardMPPS(String localAET, String remoteAET, String sopInstanceUID, Attributes attrs)
            throws Exception {
        ApplicationEntity localAE = device.getApplicationEntity(localAET);
        ApplicationEntity remoteAE = aeCache.findApplicationEntity(remoteAET);
        AAssociateRQ aarq = mkAAssociateRQ(localAE);
        Association as = localAE.connect(remoteAE, aarq);
        try {
            DimseRSP rsp = attrs.contains(Tag.ScheduledStepAttributesSequence)
                    ? as.ncreate(UID.ModalityPerformedProcedureStepSOPClass, sopInstanceUID, attrs, null)
                    : as.nset(UID.ModalityPerformedProcedureStepSOPClass, sopInstanceUID, attrs, null);
            rsp.next();
        } finally {
            try {
                as.release();
            } catch (IOException e) {
                LOG.info("{}: Failed to release association to {}", as, remoteAET);
            }
        }
    }

    private AAssociateRQ mkAAssociateRQ(ApplicationEntity localAE) {
        AAssociateRQ aarq = new AAssociateRQ();
        TransferCapability tc = localAE.getTransferCapabilityFor(UID.ModalityPerformedProcedureStepSOPClass,
                TransferCapability.Role.SCU);
        aarq.addPresentationContext(new PresentationContext(1, UID.ModalityPerformedProcedureStepSOPClass,
                tc.getTransferSyntaxes()));
        return aarq;
    }
}