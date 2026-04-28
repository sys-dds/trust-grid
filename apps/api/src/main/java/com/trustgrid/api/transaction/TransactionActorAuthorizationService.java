package com.trustgrid.api.transaction;

import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransactionActorAuthorizationService {

    void requireProvider(TransactionResponse transaction, UUID actorParticipantId, String action) {
        if (actorParticipantId == null || !actorParticipantId.equals(transaction.providerParticipantId())) {
            throw new MarketplaceActionForbiddenException(action + " requires the transaction provider");
        }
    }

    void requireRequester(TransactionResponse transaction, UUID actorParticipantId, String action) {
        if (actorParticipantId == null || !actorParticipantId.equals(transaction.requesterParticipantId())) {
            throw new MarketplaceActionForbiddenException(action + " requires the transaction requester");
        }
    }

    void requireParticipant(TransactionResponse transaction, UUID actorParticipantId, String action) {
        if (actorParticipantId == null
                || (!actorParticipantId.equals(transaction.requesterParticipantId())
                && !actorParticipantId.equals(transaction.providerParticipantId()))) {
            throw new MarketplaceActionForbiddenException(action + " requires a transaction participant");
        }
    }

    void authorize(String action, TransactionResponse transaction, UUID actorParticipantId) {
        switch (action) {
            case "start", "ship", "deliver", "proof", "claim-completion" -> requireProvider(transaction, actorParticipantId, action);
            case "confirm-completion" -> requireRequester(transaction, actorParticipantId, action);
            case "cancel", "no-show" -> requireParticipant(transaction, actorParticipantId, action);
            default -> requireParticipant(transaction, actorParticipantId, action);
        }
    }
}
