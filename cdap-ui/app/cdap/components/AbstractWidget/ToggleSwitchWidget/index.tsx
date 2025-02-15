/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

import React from 'react';
import { objectQuery } from 'services/helpers';
import ToggleSwitch from 'components/ToggleSwitch';
import { IWidgetProps } from 'components/AbstractWidget';
import { WIDGET_PROPTYPES } from 'components/AbstractWidget/constants';

interface IToggle {
  label: string;
  value: string;
}

interface IToggleWidgetProps {
  on: IToggle;
  off: IToggle;
  default?: string;
}

interface IToggleToggleSwitchProps extends IWidgetProps<IToggleWidgetProps> {}

const ToggleSwitchWidget: React.FC<IToggleToggleSwitchProps> = ({
  widgetProps,
  value,
  onChange,
  disabled,
}) => {
  const onValue = objectQuery(widgetProps, 'on', 'value') || 'on';
  const offValue = objectQuery(widgetProps, 'off', 'value') || 'off';
  const onLabel = objectQuery(widgetProps, 'on', 'label') || 'On';
  const offLabel = objectQuery(widgetProps, 'off', 'label') || 'Off';
  const isOn = value === onValue;

  function toggleSwitch() {
    onChange(isOn ? offValue : onValue);
  }
  return (
    <ToggleSwitch
      isOn={isOn}
      onToggle={toggleSwitch}
      disabled={disabled}
      onLabel={onLabel}
      offLabel={offLabel}
    />
  );
};
export default ToggleSwitchWidget;
(ToggleSwitchWidget as any).propTypes = WIDGET_PROPTYPES;
