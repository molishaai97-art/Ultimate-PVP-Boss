package com.ailingmeng.ultimatepvpboss.client;

import com.ailingmeng.ultimatepvpboss.entity.PvpBossEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

public class PvpBossRenderer extends LivingEntityRenderer<PvpBossEntity, PlayerModel<PvpBossEntity>> {
    private static final ResourceLocation STEVE = DefaultPlayerSkin.getDefaultSkin();

    public PvpBossRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.addLayer(new BossArmorLayer(this,
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR))));
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
        this.addLayer(new CustomHeadLayer<>(this, ctx.getModelSet(), ctx.getItemInHandRenderer()));
        this.addLayer(new ElytraLayer<>(this, ctx.getModelSet()));
    }

    @Override
    public void render(PvpBossEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        ClientModEvents.RenderDiagnostics.bossRendering();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PvpBossEntity entity) {
        ResourceLocation skin = BossSkinTexture.get(entity.getSkinUsername());
        return skin != null ? skin : STEVE;
    }

    @Override
    protected void scale(PvpBossEntity entity, PoseStack pose, float partial) {
        pose.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected boolean shouldShowName(PvpBossEntity entity) {
        return true;
    }

    /**
     * The boss has a fixed netherite kit, so it does not need the general-purpose armor
     * model/texture hooks. Keep this layer independent of HumanoidArmorLayer: subclassing
     * it still enters methods transformed by other mods. Only the boss's armor rendering
     * changes; equipment, enchantments, damage reduction and held items are untouched.
     */
    private static final class BossArmorLayer extends RenderLayer<PvpBossEntity, PlayerModel<PvpBossEntity>> {
        private static final ResourceLocation OUTER_TEXTURE =
                new ResourceLocation("minecraft", "textures/models/armor/netherite_layer_1.png");
        private static final ResourceLocation INNER_TEXTURE =
                new ResourceLocation("minecraft", "textures/models/armor/netherite_layer_2.png");
        private static final EquipmentSlot[] SLOTS = {
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD
        };
        private final HumanoidModel<PvpBossEntity> inner;
        private final HumanoidModel<PvpBossEntity> outer;

        private BossArmorLayer(RenderLayerParent<PvpBossEntity, PlayerModel<PvpBossEntity>> parent,
                               HumanoidModel<PvpBossEntity> inner, HumanoidModel<PvpBossEntity> outer) {
            super(parent);
            this.inner = inner;
            this.outer = outer;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, PvpBossEntity entity,
                           float limbSwing, float limbSwingAmount, float partialTick, float age,
                           float headYaw, float headPitch) {
            for (EquipmentSlot slot : SLOTS) {
                if (!(entity.getItemBySlot(slot).getItem() instanceof ArmorItem armor)
                        || armor.getEquipmentSlot() != slot || armor.getMaterial() != ArmorMaterials.NETHERITE) {
                    continue;
                }
                boolean leggings = slot == EquipmentSlot.LEGS;
                HumanoidModel<PvpBossEntity> armorModel = leggings ? inner : outer;
                getParentModel().copyPropertiesTo(armorModel);
                // Models are reused across slots, bosses and frames. Reset all visibility first.
                armorModel.setAllVisible(false);
                switch (slot) {
                    case HEAD -> armorModel.head.visible = true;
                    case CHEST -> {
                        armorModel.body.visible = true;
                        armorModel.rightArm.visible = true;
                        armorModel.leftArm.visible = true;
                    }
                    case LEGS -> {
                        armorModel.body.visible = true;
                        armorModel.rightLeg.visible = true;
                        armorModel.leftLeg.visible = true;
                    }
                    case FEET -> {
                        armorModel.rightLeg.visible = true;
                        armorModel.leftLeg.visible = true;
                    }
                    default -> { }
                }
                // Deliberately use one plain armor buffer, not the multi-consumer foil/glint
                // path or Forge's custom armor hooks. Do not flush the caller's buffers.
                armorModel.renderToBuffer(pose,
                        buffers.getBuffer(RenderType.armorCutoutNoCull(leggings ? INNER_TEXTURE : OUTER_TEXTURE)),
                        light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }
}
