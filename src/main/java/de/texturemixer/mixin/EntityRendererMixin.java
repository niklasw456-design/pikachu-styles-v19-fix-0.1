package de.texturemixer.mixin;

import de.texturemixer.NameIcon;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Haengt das Icon an den Nametag. Bewusst an EntityRenderer (nicht PlayerEntityRenderer): dessen Signatur
 * ist wegen der Generics (Entity / EntityRenderState) stabil, die Spieler-Klasse hat sich in 1.21.9+ geaendert.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void texturemixer$nameIcon(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (entity instanceof PlayerEntity player && state.displayName != null) {
            state.displayName = NameIcon.decorate(state.displayName, player.getUuid());
        }
    }
}
