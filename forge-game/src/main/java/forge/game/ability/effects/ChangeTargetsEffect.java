package forge.game.ability.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.google.common.collect.Iterables;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameObjectPredicates;
import forge.game.ability.AbilityKey;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.TriggerType;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.Localizer;

/**
 * TODO: Write javadoc for this type.
 *
 */
public class ChangeTargetsEffect extends SpellAbilityEffect {

    @Override
    public void buildSpellAbility(SpellAbility sa) {
        super.buildSpellAbility(sa);
        if (sa.usesTargeting()) {
            sa.getTargetRestrictions().setZone(ZoneType.Stack);
        }
    }

    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityEffect#resolve(forge.card.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        final List<SpellAbility> sas = getTargetSpells(sa);
        final Player activator = sa.getActivatingPlayer();
        final Player chooser = sa.hasParam("Chooser") ? getDefinedPlayersOrTargeted(sa, "Chooser").get(0) : activator;

        final MagicStack stack = activator.getGame().getStack();

        for (final SpellAbility tgtSA : sas) {
            SpellAbilityStackInstance si = stack.getInstanceMatchingSpellAbilityID(tgtSA);
            if (si == null) {
                // If there isn't a Stack Instance, there isn't really a target
                continue;
            }

            SpellAbilityStackInstance changingTgtSI = si;

            // Redirect rules read 'you MAY choose new targets' ... okay!
            // TODO: Don't even ask to change targets, if the SA and subs don't actually have targets
            if (sa.hasParam("Optional") && !chooser.getController().confirmAction(sa, null, Localizer.getInstance().getMessage("lblDoYouWantChangeAbilityTargets", tgtSA.getHostCard().toString()), null)) {
                continue;
            }
            if (sa.hasParam("ChangeSingleTarget")) {
                // 1. choose a target of target spell
                List<Pair<SpellAbilityStackInstance, GameObject>> allTargets = new ArrayList<>();
                while (changingTgtSI != null) {
                    SpellAbility changedSa = changingTgtSI.getSpellAbility();
                    if (changedSa.usesTargeting()) {
                        for (GameObject it : changedSa.getTargets())
                            allTargets.add(ImmutablePair.of(changingTgtSI, it));
                    }
                    changingTgtSI = changingTgtSI.getSubInstance();
                }
                if (allTargets.isEmpty()) {
                    return;
                }

                Pair<SpellAbilityStackInstance, GameObject> chosenTarget = chooser.getController().chooseTarget(sa, allTargets);
                // 2. prepare new target choices
                SpellAbilityStackInstance replaceIn = chosenTarget.getKey();
                GameObject oldTarget = chosenTarget.getValue();
                TargetChoices newTargetBlock = replaceIn.getTargetChoices();
                TargetChoices oldTargetBlock = newTargetBlock.clone();
                // gets the divided value from old target
                Integer div = oldTargetBlock.getDividedValue(oldTarget);
                // 3. test if updated choices would be correct.
                GameObject newTarget = Iterables.getFirst(getDefinedCardsOrTargeted(sa, "DefinedMagnet"), null);

                // CR 115.3. The same target can't be chosen multiple times for
                // any one instance of the word “target” on a spell or ability.
                if (!oldTargetBlock.contains(newTarget) && replaceIn.getSpellAbility().canTarget(newTarget)) {
                    newTargetBlock.remove(oldTarget);
                    newTargetBlock.add(newTarget);
                    if (div != null) {
                        newTargetBlock.addDividedAllocation(newTarget, div);
                    }
                    replaceIn.updateTarget(oldTargetBlock, sa.getHostCard());
                }
            } else if (sa.hasParam("RandomTarget")) {
                // CR 115.7a: changing "the target(s)" is all-or-nothing, so picks are staged
                // here first; an infeasible chain discards them and falls through to the next
                // spell in `sas`, instead of aborting the whole effect.
                List<Pair<SpellAbilityStackInstance, GameEntity>> picks = new ArrayList<>();
                boolean feasible = true;
                while (changingTgtSI != null) {
                    SpellAbility changingTgtSA = changingTgtSI.getSpellAbility();
                    if (changingTgtSA.usesTargeting()) {
                        List<GameEntity> candidates = changingTgtSA.getTargetRestrictions().getAllCandidates(changingTgtSA);
                        if (sa.hasParam("RandomTargetRestriction")) {
                            candidates.removeIf(c -> !c.isValid(sa.getParam("RandomTargetRestriction").split(","), activator, sa.getHostCard(), sa));
                        }
                        if (candidates.isEmpty()) {
                            feasible = false;
                            break;
                        }
                        picks.add(ImmutablePair.of(changingTgtSI, Aggregates.random(candidates)));
                    }
                    changingTgtSI = changingTgtSI.getSubInstance();
                }
                if (feasible) {
                    // CR 608.2f: a single spell's own targets change together (nothing stops
                    // that being simultaneous), but a redirect touching several spells at once
                    // (e.g. Chef's Kiss targeting two spells) has no such requirement across
                    // them - so each spell gets its own trigger, fired right here as its picks
                    // are committed, rather than one batch covering every spell in `sas`.
                    List<GameEntity> randomlyChosenTargets = new ArrayList<>();
                    for (Pair<SpellAbilityStackInstance, GameEntity> pick : picks) {
                        SpellAbilityStackInstance pickSI = pick.getKey();
                        SpellAbility pickSA = pickSI.getSpellAbility();
                        GameEntity choice = pick.getValue();
                        int div = pickSA.getTotalDividedValue();
                        TargetChoices oldTarget = pickSA.getTargets();
                        pickSA.resetTargets();
                        pickSA.getTargets().add(choice);
                        if (pickSA.isDividedAsYouChoose()) {
                            pickSA.addDividedAllocation(choice, div);
                        }
                        pickSI.updateTarget(oldTarget, sa.getHostCard());
                        randomlyChosenTargets.add(choice);
                    }
                    fireRandomTargetChosenTrigger(chooser, sa, randomlyChosenTargets);
                }
            } else {
                while (changingTgtSI != null) {
                    SpellAbility changingTgtSA = changingTgtSI.getSpellAbility();
                    if (changingTgtSA.usesTargeting()) {
                        if (sa.hasParam("DefinedMagnet")) {
                            GameObject newTarget = Iterables.getFirst(getDefinedCardsOrTargeted(sa, "DefinedMagnet"), null);
                            if (newTarget != null && changingTgtSA.canTarget(newTarget)) {
                                int div = changingTgtSA.getTotalDividedValue();
                                TargetChoices oldTarget = changingTgtSA.getTargets();
                                changingTgtSA.resetTargets();
                                changingTgtSA.getTargets().add(newTarget);
                                if (changingTgtSA.isDividedAsYouChoose()) {
                                    changingTgtSA.addDividedAllocation(newTarget, div);
                                }
                                changingTgtSI.updateTarget(oldTarget, sa.getHostCard());
                            }
                        } else {
                            // Update targets, with a potential new target
                            Card source = sa.getHostCard();
                            if (changingTgtSA.getTargetCard() != null) {
                                // try to use old target so "Other" restriction of Meddle works
                                source = changingTgtSA.getTargetCard();
                            }
                            Predicate<GameObject> filter = sa.hasParam("TargetRestriction") ? GameObjectPredicates.restriction(sa.getParam("TargetRestriction").split(","), activator, source, sa) : null;
                            TargetChoices oldTarget = changingTgtSA.getTargets();
                            chooser.getController().chooseNewTargetsFor(changingTgtSA, filter, false);
                            changingTgtSI.updateTarget(oldTarget, sa.getHostCard());
                        }
                    }
                    changingTgtSI = changingTgtSI.getSubInstance();
                }
            }
        }
    }

    // Fires once per targeted spell whose chain got a random replacement, right after that
    // spell's picks are committed above - see the RandomTarget branch.
    private void fireRandomTargetChosenTrigger(Player chooser, SpellAbility sa, List<GameEntity> targets) {
        if (targets.isEmpty()) {
            return;
        }
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(chooser);
        runParams.put(AbilityKey.Cause, sa);
        runParams.put(AbilityKey.Targets, targets);
        runParams.put(AbilityKey.Random, true);
        chooser.getGame().getTriggerHandler().runTrigger(TriggerType.TargetChosenAll, runParams, false);
    }
}
